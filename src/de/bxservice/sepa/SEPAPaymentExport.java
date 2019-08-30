/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package de.bxservice.sepa;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.I_C_BankAccount;
import org.compiere.model.I_C_Invoice;
import org.compiere.model.I_C_PaySelection;
import org.compiere.model.MBPBankAccount;
import org.compiere.model.MBPartner;
import org.compiere.model.MClient;
import org.compiere.model.MCurrency;
import org.compiere.model.MOrg;
import org.compiere.model.MOrgInfo;
import org.compiere.model.MPaySelectionCheck;
import org.compiere.model.MPaySelectionLine;
import org.compiere.model.MSysConfig;
import org.compiere.model.Query;
import org.compiere.model.X_C_NonBusinessDay;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.IBAN;
import org.compiere.util.PaymentExport;
import org.compiere.util.Util;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * SEPA Payment Export based on generic export example
 * 
 * @author integratio/pb
 * @author mbozem@bozem.de
 * 
 * modified by Diego Ruiz - Bx Service GmbH
 */
public class SEPAPaymentExport implements PaymentExport {
	/** Logger */
	static private CLogger s_log = CLogger.getCLogger(SEPAPaymentExport.class);

	//Main xml elements
	private static final String PAYMENT_INFO_ELEMENT = "PmtInf"; 
	private static final String SECOND_SCT_ELEMENT   = "CstmrCdtTrfInitn"; 
	private static final String SECOND_SDD_ELEMENT   = "CstmrDrctDbtInitn"; 
	private static final String ROOT_ELEMENT         = "Document";
	
	//SEPA file type
	private static final String SEPA_CREDIT_TRANSFER = "pain.001.003.03"; //Use for payments
	private static final String SEPA_DIRECT_DEBIT    = "pain.008.003.02"; //Use for collection

	private ArrayList<MPaySelectionCheck> b2bFirstPayments;
	private ArrayList<MPaySelectionCheck> cor1FirstPayments;
	private ArrayList<MPaySelectionCheck> b2bRcurPayments;
	private ArrayList<MPaySelectionCheck> cor1RcurPayments;
	
	private boolean directDebit = false;
	private String documentType;

	/**************************************************************************
	 * Export to File
	 * 
	 * @param checks
	 *            array of checks
	 * @param file
	 *            file to export checks
	 * @return number of lines
	 */
	@Override
	public int exportToFile(MPaySelectionCheck[] checks, boolean collectiveBooking, String paymentRule, File file, StringBuffer err) {

		setDocumentType(paymentRule);
		if (documentType == null) {
			s_log.log(Level.SEVERE, "Payment Rule not supported");
			return -1;
		}
		
		int noLines = checks.length;
		try {
			ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file));
			if (isDirectDebit()) {
				setDifferentPaymentTypes(checks);
				
				if (!b2bFirstPayments.isEmpty())
					addToZipFile(generateDirectDebitFile(b2bFirstPayments.toArray(new MPaySelectionCheck[b2bFirstPayments.size()]), true, true, err), out);
				if (!b2bRcurPayments.isEmpty())
					addToZipFile(generateDirectDebitFile(b2bRcurPayments.toArray(new MPaySelectionCheck[b2bRcurPayments.size()]), true, false, err), out);
				if (!cor1FirstPayments.isEmpty())
					addToZipFile(generateDirectDebitFile(cor1FirstPayments.toArray(new MPaySelectionCheck[cor1FirstPayments.size()]), false, true, err), out);
				if (!cor1RcurPayments.isEmpty())
					addToZipFile(generateDirectDebitFile(cor1RcurPayments.toArray(new MPaySelectionCheck[cor1FirstPayments.size()]), false, false, err), out);
				
			} else {
				addToZipFile(generateCreditTransferFile(checks, err), out);
			}
			out.close();
			//noLines = numberOfTransactions;
		} catch (Exception e) {
				err.append(e.toString());
				s_log.log(Level.SEVERE, "", e);
				return -1;
		}

		return noLines;
	} // exportToFile
	
	private void setDifferentPaymentTypes(MPaySelectionCheck[] checks) {
		boolean isFirstTransfer = false;
		b2bFirstPayments = new ArrayList<>();
		cor1FirstPayments = new ArrayList<>();
		b2bRcurPayments = new ArrayList<>();
		cor1RcurPayments = new ArrayList<>();
		for (MPaySelectionCheck check : checks) {
			MBPartner bPartner = MBPartner.get(Env.getCtx(), check.getC_BPartner_ID());
			MBPBankAccount bpBankAccount = getBPartnerAccount(bPartner);
			String lsString = bpBankAccount.get_ValueAsString(MBPBankAccountHelper.COLUMNNAME_SEPASDDSCHEME);
			if (lsString == "")
				throw new AdempiereException("Bank Account without a SEPA Mandate Type set: "+ bpBankAccount.getA_Name());
			isFirstTransfer = !bpBankAccount.get_ValueAsBoolean(MBPBankAccountHelper.COLUMNNAME_ISTRANSFERRED);
			
			if (lsString.equals("B2B")) {
				if (isFirstTransfer) {
					b2bFirstPayments.add(check);
					bpBankAccount.set_ValueNoCheck(MBPBankAccountHelper.COLUMNNAME_ISTRANSFERRED, Boolean.TRUE);
					bpBankAccount.saveEx();
				}
				else 
					b2bRcurPayments.add(check);
			} else if (lsString.equals("COR1")) {
				if (isFirstTransfer) {
					cor1FirstPayments.add(check);
					bpBankAccount.set_ValueNoCheck(MBPBankAccountHelper.COLUMNNAME_ISTRANSFERRED, Boolean.TRUE);
					bpBankAccount.saveEx();
				}
				else 
					cor1RcurPayments.add(check);
			}
		}
	}

	private File generateCreditTransferFile(MPaySelectionCheck[] checks, StringBuffer err) throws Exception {
		
		String creationFileDate = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(System.currentTimeMillis()) ;
		File xmlFile = File.createTempFile("SEPA-Credit-Transfer-" + creationFileDate, ".xml");
		
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = dbf.newDocumentBuilder();
		Document document = builder.newDocument();

		MClient client = MClient.get(Env.getCtx());

		String msgId;
		String creationDate;
		int numberOfTransactions = 0;
		String initiatorName;
		BigDecimal ctrlSum = BigDecimal.ZERO;

		for (int i = 0; i < checks.length; i++) {
			MPaySelectionCheck mpp = checks[i];
			ctrlSum = ctrlSum.add(mpp.getPayAmt());
			numberOfTransactions++;
		}

		MPaySelectionCheck firstPaySelectionCheck = checks[0];
		I_C_PaySelection firstPaySelection = firstPaySelectionCheck.getC_PaySelection();

		if (firstPaySelection.getAD_Org_ID() != 0)
			initiatorName = MOrg.get(Env.getCtx(), firstPaySelection.getAD_Org_ID()).getName();
		else
			initiatorName = client.getName();

		msgId = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(firstPaySelection.getCreated());

		creationDate = new SimpleDateFormat("yyyy-MM-dd").format(System.currentTimeMillis()) + "T"
				+ new SimpleDateFormat("HH:mm:ss").format(System.currentTimeMillis()) + ".000Z";

		String paymentInfoId = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(System.currentTimeMillis())+ "/TRF";

		
		//Header
		Element root = document.createElement(ROOT_ELEMENT);
		root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
		root.setAttribute("xmlns:xsd", "http://www.w3.org/2001/XMLSchema");
		root.setAttribute("xmlns", "urn:iso:std:iso:20022:tech:xsd:" + documentType);
		root.setAttribute("xsi:schemaLocation", "urn:iso:std:iso:20022:tech:xsd:" + documentType + " " + documentType + ".xsd");

		//begin of second level node
		Element secondNodeInitnElement = document.createElement(SECOND_SCT_ELEMENT);

		//Group header element same for both cases
		Element GrpHdrElement = document.createElement("GrpHdr");
		GrpHdrElement.appendChild(document.createElement("MsgId")).setTextContent(iSEPA_ConvertSign(msgId, 35));
		GrpHdrElement.appendChild(document.createElement("CreDtTm")).setTextContent(iSEPA_ConvertSign(creationDate));
		GrpHdrElement.appendChild(document.createElement("NbOfTxs")).setTextContent(String.valueOf(numberOfTransactions));
		GrpHdrElement.appendChild(document.createElement("InitgPty")).appendChild(document.createElement("Nm")).setTextContent(iSEPA_ConvertSign(initiatorName, 70));
		secondNodeInitnElement.appendChild(GrpHdrElement);
		
		//Begin of PmtInf
		Element paymentInfoElement = document.createElement(PAYMENT_INFO_ELEMENT);

		paymentInfoElement.appendChild(document.createElement("PmtInfId"))
		.setTextContent(iSEPA_ConvertSign(paymentInfoId, 35));
		paymentInfoElement.appendChild(document.createElement("PmtMtd")).setTextContent("TRF");
		paymentInfoElement.appendChild(document.createElement("BtchBookg")).setTextContent("true");
		paymentInfoElement.appendChild(document.createElement("NbOfTxs"))
		.setTextContent(String.valueOf(numberOfTransactions));
		paymentInfoElement.appendChild(document.createElement("CtrlSum")).setTextContent(String.valueOf(ctrlSum));
		
		Element PmtTpInfElement = document.createElement("PmtTpInf");
		PmtTpInfElement.appendChild(document.createElement("SvcLvl"))
					.appendChild(document.createElement("Cd")).setTextContent("SEPA");
		
		I_C_BankAccount bankAccount = firstPaySelection.getC_BankAccount();
		
		String executionDate = new SimpleDateFormat("yyyy-MM-dd").format(getShiftedDate(firstPaySelection.getPayDate()));
		String dbtr_Name = MOrg.get(Env.getCtx(), firstPaySelection.getAD_Org_ID()).getName();
		String dbtrAcct_IBAN = IBAN.normalizeIBAN(bankAccount.getIBAN());
		String dbtrAcct_BIC = bankAccount.getC_Bank().getSwiftCode();

		if (!IBAN.isValid(dbtrAcct_IBAN)) {
			err.append("IBAN " + dbtrAcct_IBAN + " is not valid.");
			throw new Exception();
		}

		if (!Util.isEmpty(dbtrAcct_BIC) && dbtrAcct_BIC.length() > 11) {
			err.append("BIC/SWIFTCode " + dbtrAcct_BIC + " is not valid.");
			throw new Exception();
		}
		
		paymentInfoElement.appendChild(PmtTpInfElement);
		paymentInfoElement.appendChild(document.createElement("ReqdExctnDt"))
					.setTextContent(iSEPA_ConvertSign(executionDate));
		paymentInfoElement.appendChild(document.createElement("Dbtr")).appendChild(document.createElement("Nm"))
					.setTextContent(iSEPA_ConvertSign(dbtr_Name, 70));
		paymentInfoElement.appendChild(document.createElement("DbtrAcct")).appendChild(document.createElement("Id"))
					.appendChild(document.createElement("IBAN")).setTextContent(dbtrAcct_IBAN);
		paymentInfoElement.appendChild(document.createElement("DbtrAgt"))
					.appendChild(document.createElement("FinInstnId")).appendChild(document.createElement("BIC"))
					.setTextContent(iSEPA_ConvertSign(dbtrAcct_BIC));
		paymentInfoElement.appendChild(document.createElement("ChrgBr")).setTextContent("SLEV");
		
		
		for (MPaySelectionCheck check : checks) {
			if (check == null)
				continue;
			
			paymentInfoElement.appendChild(getCreditTransferTrxInfo(check, document, err));
		}
		
		secondNodeInitnElement.appendChild(paymentInfoElement);
		root.appendChild(secondNodeInitnElement);
		document.appendChild(root);
		
		convertToXMLFile(document, xmlFile);
		
		return xmlFile;
	}
	
	private File generateDirectDebitFile(MPaySelectionCheck[] checks, boolean isB2B, boolean isFirstTransfer, StringBuffer err) throws Exception {
		
		StringBuilder fileName = new StringBuilder("SEPA-Direct-Debit-");
		fileName.append(new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(System.currentTimeMillis()));
		fileName.append(isB2B ? "B2B" : "COR1");
		fileName.append(isFirstTransfer ? "FRST" : "RCUR");
		File xmlFile = File.createTempFile(fileName.toString(), ".xml");
		
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = dbf.newDocumentBuilder();
		Document document = builder.newDocument();

		MClient client = MClient.get(Env.getCtx());

		String msgId;
		String creationDate;
		int numberOfTransactions = 0;
		String initiatorName;
		BigDecimal ctrlSum = BigDecimal.ZERO;

		for (int i = 0; i < checks.length; i++) {
			MPaySelectionCheck mpp = checks[i];
			ctrlSum = ctrlSum.add(mpp.getPayAmt());
			numberOfTransactions++;
		}

		MPaySelectionCheck firstPaySelectionCheck = checks[0];
		I_C_PaySelection firstPaySelection = firstPaySelectionCheck.getC_PaySelection();

		if (firstPaySelection.getAD_Org_ID() != 0)
			initiatorName = MOrg.get(Env.getCtx(), firstPaySelection.getAD_Org_ID()).getName();
		else
			initiatorName = client.getName();

		msgId = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(firstPaySelection.getCreated());
		
		StringBuilder paymentInfoId = new StringBuilder(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(System.currentTimeMillis()));
		paymentInfoId.append(isB2B ? " /B2B" : " /COR1");
		paymentInfoId.append(isFirstTransfer ? "-FRST" : "-RCUR");
		
		
		creationDate = new SimpleDateFormat("yyyy-MM-dd").format(System.currentTimeMillis()) + "T"
				+ new SimpleDateFormat("HH:mm:ss").format(System.currentTimeMillis()) + ".000Z";

		//Header
		Element root = document.createElement(ROOT_ELEMENT);
		root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
		root.setAttribute("xmlns:xsd", "http://www.w3.org/2001/XMLSchema");
		root.setAttribute("xmlns", "urn:iso:std:iso:20022:tech:xsd:" + documentType);
		root.setAttribute("xsi:schemaLocation", "urn:iso:std:iso:20022:tech:xsd:" + documentType + " " + documentType + ".xsd");

		//begin of second level node
		Element secondNodeInitnElement = document.createElement(SECOND_SDD_ELEMENT);

		//Group header element same for both cases
		Element GrpHdrElement = document.createElement("GrpHdr");
		GrpHdrElement.appendChild(document.createElement("MsgId")).setTextContent(iSEPA_ConvertSign(msgId, 35));
		GrpHdrElement.appendChild(document.createElement("CreDtTm")).setTextContent(iSEPA_ConvertSign(creationDate));
		GrpHdrElement.appendChild(document.createElement("NbOfTxs")).setTextContent(String.valueOf(numberOfTransactions));
		GrpHdrElement.appendChild(document.createElement("InitgPty")).appendChild(document.createElement("Nm")).setTextContent(iSEPA_ConvertSign(initiatorName, 70));
		secondNodeInitnElement.appendChild(GrpHdrElement);
		
		//Begin of PmtInf
		Element paymentInfoElement = document.createElement(PAYMENT_INFO_ELEMENT);

		paymentInfoElement.appendChild(document.createElement("PmtInfId"))
				.setTextContent(iSEPA_ConvertSign(paymentInfoId.toString(), 35));
		paymentInfoElement.appendChild(document.createElement("PmtMtd")).setTextContent("DD");
		paymentInfoElement.appendChild(document.createElement("BtchBookg")).setTextContent("true");
		paymentInfoElement.appendChild(document.createElement("NbOfTxs"))
		.setTextContent(String.valueOf(numberOfTransactions));
		paymentInfoElement.appendChild(document.createElement("CtrlSum")).setTextContent(String.valueOf(ctrlSum));
		
		Element PmtTpInfElement = document.createElement("PmtTpInf");
		PmtTpInfElement.appendChild(document.createElement("SvcLvl"))
					.appendChild(document.createElement("Cd")).setTextContent("SEPA");
		
		I_C_BankAccount bankAccount = firstPaySelection.getC_BankAccount();
		
		String executionDate = new SimpleDateFormat("yyyy-MM-dd").format(getShiftedDate(firstPaySelection.getPayDate()));
		String dbtr_Name = MOrg.get(Env.getCtx(), firstPaySelection.getAD_Org_ID()).getName();
		String dbtrAcct_IBAN = IBAN.normalizeIBAN(bankAccount.getIBAN());
		String dbtrAcct_BIC = bankAccount.getC_Bank().getSwiftCode();

		if (!IBAN.isValid(dbtrAcct_IBAN)) {
			err.append("IBAN " + dbtrAcct_IBAN + " is not valid.");
			throw new Exception();
		}

		if (!Util.isEmpty(dbtrAcct_BIC) && dbtrAcct_BIC.length() > 11) {
			err.append("BIC/SWIFTCode " + dbtrAcct_BIC + " is not valid.");
			throw new Exception();
		}
		
		PmtTpInfElement.appendChild(document.createElement("LclInstrm"))
					.appendChild(document.createElement("Cd")).setTextContent(isB2B ? "B2B" : "COR1");
		PmtTpInfElement.appendChild(document.createElement("SeqTp")).setTextContent(isFirstTransfer ? "FRST" : "RCUR");
		paymentInfoElement.appendChild(PmtTpInfElement);

		paymentInfoElement.appendChild(document.createElement("ReqdColltnDt")).setTextContent(iSEPA_ConvertSign(executionDate));
		paymentInfoElement.appendChild(document.createElement("Cdtr")).appendChild(document.createElement("Nm"))
						.setTextContent(iSEPA_ConvertSign(dbtr_Name, 70));
		paymentInfoElement.appendChild(document.createElement("CdtrAcct")).appendChild(document.createElement("Id"))
						.appendChild(document.createElement("IBAN")).setTextContent(dbtrAcct_IBAN);
		paymentInfoElement.appendChild(document.createElement("CdtrAgt"))
						.appendChild(document.createElement("FinInstnId")).appendChild(document.createElement("BIC"))
						.setTextContent(iSEPA_ConvertSign(dbtrAcct_BIC));
		paymentInfoElement.appendChild(document.createElement("ChrgBr")).setTextContent("SLEV");
		
		for (MPaySelectionCheck check : checks) {
			if (check == null)
				continue;
			
			paymentInfoElement.appendChild(getDirectDebitTrxInfo(check, document, err));
		}
		
		secondNodeInitnElement.appendChild(paymentInfoElement);
		root.appendChild(secondNodeInitnElement);
		document.appendChild(root);
		
		convertToXMLFile(document, xmlFile);
		
		return xmlFile;
	}
	
	private Element getCreditTransferTrxInfo(MPaySelectionCheck paySelectionCheck, Document document, StringBuffer err) throws Exception {

		String pmtId = getEndToEndId(paySelectionCheck);
		String creditorName;
		String CdtrAcct_BIC;
		String CdtrAcct_IBAN;
		String unverifiedReferenceLine;

		unverifiedReferenceLine = getUnverifiedReferenceLine(paySelectionCheck);

		BigDecimal payAmt = paySelectionCheck.getPayAmt();

		MBPartner bPartner = MBPartner.get(Env.getCtx(), paySelectionCheck.getC_BPartner_ID());
		creditorName = bPartner.getName();

		MBPBankAccount bpBankAccount = getBPartnerAccount(bPartner);
		if (bpBankAccount == null) {
			err.append("BPARTNER " + bPartner.getName() + " does not have a valid bank account");
			throw new Exception();
		}
		CdtrAcct_IBAN = IBAN.normalizeIBAN(bpBankAccount.getIBAN());
		CdtrAcct_BIC = bpBankAccount.getSwiftCode();

		if (!IBAN.isValid(CdtrAcct_IBAN)) {
			err.append("IBAN " + CdtrAcct_IBAN + " is not valid. Creditor: " + creditorName);
			throw new Exception();
		}

		Element CdtTrfTxInfElement = document.createElement("CdtTrfTxInf");

		CdtTrfTxInfElement.appendChild(document.createElement("PmtId"))
				.appendChild(document.createElement("EndToEndId")).setTextContent(iSEPA_ConvertSign(pmtId, 35));

		Element InstdAmtElement = document.createElement("InstdAmt");
		InstdAmtElement.setAttribute("Ccy",
				MCurrency.getISO_Code(Env.getCtx(), paySelectionCheck.getParent().getC_Currency_ID()));
		InstdAmtElement.setTextContent(String.valueOf(payAmt));

		CdtTrfTxInfElement.appendChild(document.createElement("Amt")).appendChild(InstdAmtElement);
		CdtTrfTxInfElement.appendChild(document.createElement("CdtrAgt"))
					.appendChild(document.createElement("FinInstnId")).appendChild(document.createElement("BIC"))
					.setTextContent(iSEPA_ConvertSign(CdtrAcct_BIC));
		CdtTrfTxInfElement.appendChild(document.createElement("Cdtr")).appendChild(document.createElement("Nm"))
					.setTextContent(iSEPA_ConvertSign(creditorName, 70));
		CdtTrfTxInfElement.appendChild(document.createElement("CdtrAcct"))
					.appendChild(document.createElement("Id")).appendChild(document.createElement("IBAN"))
					.setTextContent(CdtrAcct_IBAN);
		CdtTrfTxInfElement.appendChild(document.createElement("RmtInf"))
					.appendChild(document.createElement("Ustrd"))
					.setTextContent(iSEPA_ConvertSign(unverifiedReferenceLine, 140));
		
		return CdtTrfTxInfElement;
	}
	
	private Element getDirectDebitTrxInfo(MPaySelectionCheck paySelectionCheck, Document document, StringBuffer err) throws Exception {

		String pmtId = getEndToEndId(paySelectionCheck);
		String debitorName;
		String dbtrAcct_BIC;
		String dbtrAcct_IBAN;
		String unverifiedReferenceLine;

		unverifiedReferenceLine = getUnverifiedReferenceLine(paySelectionCheck);

		BigDecimal payAmt = paySelectionCheck.getPayAmt();

		MBPartner bPartner = MBPartner.get(Env.getCtx(), paySelectionCheck.getC_BPartner_ID());
		debitorName = bPartner.getName();

		MBPBankAccount bpBankAccount = getBPartnerAccount(bPartner);
		if (bpBankAccount == null) {
			err.append("BPARTNER " + bPartner.getName() + " does not have a valid bank account");
			throw new Exception();
		}
		dbtrAcct_IBAN = IBAN.normalizeIBAN(bpBankAccount.getIBAN());
		dbtrAcct_BIC = bpBankAccount.getSwiftCode();

		if (!IBAN.isValid(dbtrAcct_IBAN)) {
			err.append("IBAN " + dbtrAcct_IBAN + " is not valid. Creditor: " + debitorName);
			throw new Exception();
		}

		Element drctDbtTxInfElement = document.createElement("DrctDbtTxInf");

		drctDbtTxInfElement.appendChild(document.createElement("PmtId"))
							.appendChild(document.createElement("EndToEndId")).setTextContent(iSEPA_ConvertSign(pmtId, 35));

		Element InstdAmtElement = document.createElement("InstdAmt");
		InstdAmtElement.setAttribute("Ccy",
				MCurrency.getISO_Code(Env.getCtx(), paySelectionCheck.getParent().getC_Currency_ID()));
		InstdAmtElement.setTextContent(String.valueOf(payAmt));
		drctDbtTxInfElement.appendChild(InstdAmtElement);
		
		String signatureDate = new SimpleDateFormat("yyyy-MM-dd").format(bpBankAccount.get_Value(MBPBankAccountHelper.COLUMNNAME_DATEDOC));

		Element drctDbtTxElement = document.createElement("DrctDbtTx");
		Element mndtRltdInfElement = document.createElement("MndtRltdInf");
		mndtRltdInfElement.appendChild(document.createElement("MndtId")).setTextContent(bpBankAccount.get_ValueAsString(MBPBankAccountHelper.COLUMNNAME_MNDTID));
		mndtRltdInfElement.appendChild(document.createElement("DtOfSgntr")).setTextContent(signatureDate);
		mndtRltdInfElement.appendChild(document.createElement("AmdmntInd")).setTextContent("false");
		drctDbtTxElement.appendChild(mndtRltdInfElement);
		
		Element cdtrSchmeIdElement = document.createElement("CdtrSchmeId");
		Element cdtrIdElement = document.createElement("Id");
		Element prvtIdElement = document.createElement("PrvtId");
		Element othrElement = document.createElement("Othr");
		Element othrIDElement = document.createElement("Id");
		String creditorIdentifier =  MOrgInfo.get(Env.getCtx(), paySelectionCheck.getAD_Org_ID(), null).get_ValueAsString(MOrgHelper.COLUMNNAME_AD_ORG_CREDITORIDENTIFIER);
		othrIDElement.setTextContent(creditorIdentifier);
		othrElement.appendChild(othrIDElement);
		othrElement.appendChild(document.createElement("SchmeNm"))
					.appendChild(document.createElement("Prtry")).setTextContent("SEPA");
		prvtIdElement.appendChild(othrElement);
		cdtrIdElement.appendChild(prvtIdElement);
		cdtrSchmeIdElement.appendChild(cdtrIdElement);
		drctDbtTxElement.appendChild(cdtrSchmeIdElement);
		drctDbtTxInfElement.appendChild(drctDbtTxElement);
		
		drctDbtTxInfElement.appendChild(document.createElement("DbtrAgt"))
							.appendChild(document.createElement("FinInstnId")).appendChild(document.createElement("BIC"))
							.setTextContent(iSEPA_ConvertSign(dbtrAcct_BIC));

		drctDbtTxInfElement.appendChild(document.createElement("Dbtr")).appendChild(document.createElement("Nm"))
							.setTextContent(iSEPA_ConvertSign(debitorName, 70));
		drctDbtTxInfElement.appendChild(document.createElement("DbtrAcct"))
							.appendChild(document.createElement("Id")).appendChild(document.createElement("IBAN"))
							.setTextContent(dbtrAcct_IBAN);
		
		drctDbtTxInfElement.appendChild(document.createElement("RmtInf"))
		.appendChild(document.createElement("Ustrd"))
		.setTextContent(iSEPA_ConvertSign(unverifiedReferenceLine, 140));
		
		return drctDbtTxInfElement;
	}
	
	private void convertToXMLFile(Document document, File xmlFile) throws Exception {
		DOMSource domSource = new DOMSource(document);
		StreamResult streamResult = new StreamResult(xmlFile);
		TransformerFactory tf = TransformerFactory.newInstance();

		Transformer serializer = tf.newTransformer();
		serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		serializer.setOutputProperty(OutputKeys.INDENT, "yes");
		serializer.transform(domSource, streamResult);
	}

	private void addToZipFile(File file, ZipOutputStream zos) throws FileNotFoundException, IOException {
		FileInputStream fis = new FileInputStream(file);
		ZipEntry zipEntry = new ZipEntry(file.getName());
		zos.putNextEntry(zipEntry);

		byte[] bytes = new byte[1024];
		int length;
		while ((length = fis.read(bytes)) >= 0) {
			zos.write(bytes, 0, length);
		}

		zos.closeEntry();
		fis.close();
	}
	
	/**
	 * 
	 * Generate unstructured reference line
	 * 
	 * @param mpp
	 *            check
	 * @return String with the reference line
	 * 
	 *         see EACT www.eact.eu/main.php?page=SEPA
	 */
	private String getUnverifiedReferenceLine(MPaySelectionCheck mpp) {
		MPaySelectionLine[] mPaySelectionLines = mpp.getPaySelectionLines(true);
		
		SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY);


		StringBuffer remittanceInformationSB = new StringBuffer();

		for (MPaySelectionLine mPaySelectionLine : mPaySelectionLines) {
			String documentNo = null;
			I_C_Invoice invoice = mPaySelectionLine.getC_Invoice();
			if (invoice != null) {
				if (remittanceInformationSB.length() != 0) {
					remittanceInformationSB.append(",");
				}

				remittanceInformationSB.append(dateFormat.format(invoice.getDateInvoiced()));
				remittanceInformationSB.append(" ");
				documentNo = invoice.getDocumentNo();
				if (documentNo != null && documentNo.length() > 0) {
					remittanceInformationSB.append(documentNo);
				}
				
				if (invoice.getC_Order() != null) {
					String orderNo = invoice.getC_Order().getDocumentNo();
					if (!Util.isEmpty(orderNo)) {
						remittanceInformationSB.append("/");
						remittanceInformationSB.append(orderNo);
					}
				}
				if (invoice.getPOReference() != null) {
					if (!Util.isEmpty(invoice.getPOReference())) {
						remittanceInformationSB.append(" ");
						remittanceInformationSB.append(invoice.getPOReference());
					}
				}
				remittanceInformationSB.append(" ");
				remittanceInformationSB.append(NumberFormat.getNumberInstance(Locale.GERMANY).format(invoice.getGrandTotal()));
			}
		}
		if (remittanceInformationSB.length() >= 136)
			return remittanceInformationSB.toString().substring(0, 136) + " u.a.";

		return remittanceInformationSB.toString();
	} // getUnverifiedReferenceLine

	private String getEndToEndId(MPaySelectionCheck mpp) {

		StringBuilder endToEndID = new StringBuilder();
		MPaySelectionLine[] mPaySelectionLines = mpp.getPaySelectionLines(true);

		for (MPaySelectionLine mPaySelectionLine : mPaySelectionLines) {
			String documentNo = null;
			I_C_Invoice invoice = mPaySelectionLine.getC_Invoice();
			if (invoice != null) {
				documentNo = invoice.getDocumentNo();
				if (documentNo != null && documentNo.length() > 0) {
					endToEndID.append(documentNo);
					endToEndID.append("/");
				}
			}
		}

		return endToEndID.toString().substring(0, endToEndID.toString().length()-1);  //remove last /
	}

	/**
	 * Get Vendor/Customer Bank Account Information Based on BP_
	 * 
	 * @param bPartner
	 *            BPartner
	 * @return Account of business partner
	 */

	private MBPBankAccount getBPartnerAccount(MBPartner bPartner) {

		MBPBankAccount[] bpBankAccounts = bPartner.getBankAccounts(true);
		MBPBankAccount bpBankAccount = null;
		for (MBPBankAccount bpBankAccountTemp : bpBankAccounts) {
			if (bpBankAccountTemp.isActive() && !Util.isEmpty(bpBankAccountTemp.getIBAN())) {

				if (isDirectDebit() && bpBankAccountTemp.isDirectDebit())
					bpBankAccount = bpBankAccountTemp;
				else if (!isDirectDebit() && bpBankAccountTemp.isDirectDeposit())
					bpBankAccount = bpBankAccountTemp;

				if (bpBankAccount != null)
					break;
			}
		}
		return bpBankAccount;
	} // getBPartnerAccount

	public static String iSEPA_ConvertSign(String text) {
		text = text.replace("ä", "ae");
		text = text.replace("ö", "oe");
		text = text.replace("ü", "ue");
		text = text.replace("Ä", "Ae");
		text = text.replace("Ö", "Oe");
		text = text.replace("Ü", "Ue");
		text = text.replace("ß", "ss");
		text = text.replace("é", "e");
		text = text.replace("è", "e");
		text = text.replace("&", "und");
		text = text.replace("<", "&lt;");
		text = text.replace(">", "&gt;");
		text = text.replace("\"", "&quot;");
		text = text.replace("'", "&apos;");
		return text;
	}

	public static String iSEPA_ConvertSign(String text, int maxLength) {
		String targettext = iSEPA_ConvertSign(text);

		if (targettext.length() <= maxLength) {
			return targettext;
		} else {
			return targettext.substring(0, maxLength);
		}
	}

	public void setDocumentType(String paymentRule) {
		if (MPaySelectionCheck.PAYMENTRULE_DirectDebit.equals(paymentRule)) {
			documentType = SEPA_DIRECT_DEBIT;
			directDebit = true;
		}
		else if (MPaySelectionCheck.PAYMENTRULE_DirectDeposit.equals(paymentRule)) {
			documentType = SEPA_CREDIT_TRANSFER;
			directDebit = false;
		}
	}

	@Override
	public String getFilenamePrefix() {
		String creationDate = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(System.currentTimeMillis()) ;
		return "SEPA-" + creationDate ;
	}

	@Override
	public String getFilenameSuffix() {
		return ".zip";
	}

	@Override
	public String getContentType() {
		return "application/zip";
	}

	public boolean supportsDepositBatch() {
		return false;
	}

	public boolean supportsSeparateBooking() {
		return true;
	}

	public boolean getDefaultDepositBatch() {
		return false;
	}

	public boolean isDirectDebit() {
		return directDebit;
	}

	public void setDirectDebit(boolean isDirectDebit) {
		this.directDebit = isDirectDebit;
	}
	
	/**
	 * Adds n days to the original date
	 * @param originalDate
	 * @return date shifted n days
	 */
	private Timestamp getShiftedDate(Timestamp originalDate) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(originalDate);
		cal.add(Calendar.DAY_OF_WEEK, MSysConfig.getIntValue("SEPA_SHIFT_DAYS", 0, Env.getAD_Client_ID(Env.getCtx())));
		
		while (!isValidBankDate(cal)) {
			if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY)
				cal.add(Calendar.DAY_OF_WEEK, 3);
			else if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY)
				cal.add(Calendar.DAY_OF_WEEK, 2);
			else 
				cal.add(Calendar.DAY_OF_WEEK, 1);
		}
		
		return new Timestamp(cal.getTime().getTime());
	}

	/**
	 * Checks if the date is valid for the bank calendar 
	 */
	private boolean isValidBankDate(Calendar originalDate) {
		int dow = originalDate.get(Calendar.DAY_OF_WEEK);
		boolean isWeekday = ((dow >= Calendar.MONDAY) && (dow <= Calendar.FRIDAY));

		if (!isWeekday)
			return false;
		
		X_C_NonBusinessDay nonWorkingDay = new Query(Env.getCtx(), X_C_NonBusinessDay.Table_Name, 
				"TRUNC(" + X_C_NonBusinessDay.COLUMNNAME_Date1 + ")=? AND "+ X_C_NonBusinessDay.COLUMNNAME_Name +  " LIKE ?", null)
				.setParameters(new Object[]{new Timestamp(originalDate.getTime().getTime()), MSysConfig.getValue("SEPA_BANKHOLIDAY_KEYWORD", "", Env.getAD_Client_ID(Env.getCtx()))})
				.setOnlyActiveRecords(true)
				.first();
		
		if (nonWorkingDay != null)
			return false;

		return true;
	}

} // PaymentExport
