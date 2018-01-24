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
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.logging.Level;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.compiere.model.I_C_BankAccount;
import org.compiere.model.I_C_Invoice;
import org.compiere.model.I_C_PaySelection;
import org.compiere.model.MBPBankAccount;
import org.compiere.model.MBPartner;
import org.compiere.model.MClient;
import org.compiere.model.MCurrency;
import org.compiere.model.MOrg;
import org.compiere.model.MPaySelectionCheck;
import org.compiere.model.MPaySelectionLine;
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
 */
public class SEPAPaymentExport implements PaymentExport {
	/** Logger */
	static private CLogger s_log = CLogger.getCLogger(SEPAPaymentExport.class);
	
	private final String SEPA_CREDIT_TRANSFER = "pain.001.002.03"; //Use for payments
	private final String SEPA_DIRECT_DEBIT    = "pain.008.003.02"; //Use for collection
	private boolean isDirectDebit = false;
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
		
		SetDocumentType(paymentRule);
		if (documentType == null) {
			s_log.log(Level.SEVERE, "Payment Rule not supported");
			return -1;
		}

		int noLines = 0;
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = dbf.newDocumentBuilder();
			Document document = builder.newDocument();

			MClient client = MClient.get(Env.getCtx());

			String msgId;
			String creationDate;
			int numberOfTransactions = 0;
			String initiatorName;
			BigDecimal ctrlSum;
			String executionDate;
			String dbtrAcct_IBAN;
			String dbtrAcct_BIC;

			ctrlSum = BigDecimal.ZERO;

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
			String dbtr_Name = initiatorName; //TODO: WHEN COLLECTION?

			msgId = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(firstPaySelection.getCreated());
			String paymentInfoId = msgId;

			executionDate = new SimpleDateFormat("yyyy-MM-dd").format(firstPaySelection.getPayDate());
			creationDate = new SimpleDateFormat("yyyy-MM-dd").format(System.currentTimeMillis()) + "T"
					+ new SimpleDateFormat("HH:mm:ss").format(System.currentTimeMillis()) + ".000Z";

			I_C_BankAccount bankAccount = firstPaySelection.getC_BankAccount();
			dbtrAcct_IBAN = IBAN.normalizeIBAN(bankAccount.getIBAN());
			dbtrAcct_BIC = bankAccount.getC_Bank().getSwiftCode();

			if (!IBAN.isValid(dbtrAcct_IBAN)) {
				err.append("IBAN " + dbtrAcct_IBAN + " is not valid.");
				return -1;
			}

			if (!Util.isEmpty(dbtrAcct_BIC) && dbtrAcct_BIC.length() > 11) {
				err.append("BIC/SWIFTCode " + dbtrAcct_BIC + " is not valid.");
				return -1;
			}

			Element root = document.createElement("Document");
			root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
			root.setAttribute("xmlns:xsd", "http://www.w3.org/2001/XMLSchema");
			root.setAttribute("xmlns", "urn:iso:std:iso:20022:tech:xsd:" + documentType);
			
			
			//begin of CstmrCdtTrfInitnElement
			Element CstmrCdtTrfInitnElement = document.createElement("CstmrCdtTrfInitn");
			
			Element GrpHdrElement = document.createElement("GrpHdr");
			GrpHdrElement.appendChild(document.createElement("MsgId")).setTextContent(iSEPA_ConvertSign(msgId, 35));
			GrpHdrElement.appendChild(document.createElement("CreDtTm")).setTextContent(iSEPA_ConvertSign(creationDate));
			GrpHdrElement.appendChild(document.createElement("NbOfTxs")).setTextContent(String.valueOf(numberOfTransactions));
			GrpHdrElement.appendChild(document.createElement("InitgPty")).appendChild(document.createElement("Nm")).setTextContent(iSEPA_ConvertSign(initiatorName, 70));
			CstmrCdtTrfInitnElement.appendChild(GrpHdrElement);

			//Begin of PmtInf
			Element PmtInfElement = document.createElement("PmtInf");

			PmtInfElement.appendChild(document.createElement("PmtInfId"))
					.setTextContent(iSEPA_ConvertSign(paymentInfoId, 35));
			PmtInfElement.appendChild(document.createElement("PmtMtd")).setTextContent("TRF");
			PmtInfElement.appendChild(document.createElement("BtchBookg")).setTextContent("true");
			PmtInfElement.appendChild(document.createElement("NbOfTxs"))
					.setTextContent(String.valueOf(numberOfTransactions));
			PmtInfElement.appendChild(document.createElement("CtrlSum")).setTextContent(String.valueOf(ctrlSum));
			PmtInfElement.appendChild(document.createElement("PmtTpInf")).appendChild(document.createElement("SvcLvl"))
					.appendChild(document.createElement("Cd")).setTextContent("SEPA");
			PmtInfElement.appendChild(document.createElement("ReqdExctnDt"))
					.setTextContent(iSEPA_ConvertSign(executionDate));
			PmtInfElement.appendChild(document.createElement("Dbtr")).appendChild(document.createElement("Nm"))
					.setTextContent(iSEPA_ConvertSign(dbtr_Name, 70));
			PmtInfElement.appendChild(document.createElement("DbtrAcct")).appendChild(document.createElement("Id"))
					.appendChild(document.createElement("IBAN")).setTextContent(dbtrAcct_IBAN);
			PmtInfElement.appendChild(document.createElement("DbtrAgt"))
					.appendChild(document.createElement("FinInstnId")).appendChild(document.createElement("BIC"))
					.setTextContent(iSEPA_ConvertSign(dbtrAcct_BIC));
			PmtInfElement.appendChild(document.createElement("ChrgBr")).setTextContent("SLEV");

			//Begin of CdtTrfTxInf
			for (int i = 0; i < checks.length; i++) {
				MPaySelectionCheck paySelectionCheck = checks[i];

				if (paySelectionCheck == null)
					continue;
				
				String pmtId = getEndToEndId(paySelectionCheck);
				String creditorName;
				String CdtrAcct_BIC;
				String CdtrAcct_IBAN;
				String unverifiedReferenceLine;

				unverifiedReferenceLine = getUnverifiedReferenceLine(paySelectionCheck);
				
				BigDecimal payAmt = paySelectionCheck.getPayAmt();
				if (payAmt.compareTo(BigDecimal.ZERO) <= 0) {
						payAmt=payAmt.negate();
				}

				MBPartner bPartner = new MBPartner(Env.getCtx(), paySelectionCheck.getC_BPartner_ID(), null);
				creditorName = bPartner.getName();

				MBPBankAccount bpBankAccount = getBPartnerAccount(bPartner);
				if (bpBankAccount == null) {
					err.append("BPARTNER " + bPartner.getName() + " does not have a valid bank account");
					return -1;
				}
				CdtrAcct_IBAN = IBAN.normalizeIBAN(bpBankAccount.getIBAN());
				CdtrAcct_BIC = bpBankAccount.getSwiftCode();

				if (!IBAN.isValid(CdtrAcct_IBAN)) {
					err.append("IBAN " + CdtrAcct_IBAN + " is not valid. Creditor: " + creditorName);
					return -1;
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
				PmtInfElement.appendChild(CdtTrfTxInfElement);

			}

			CstmrCdtTrfInitnElement.appendChild(PmtInfElement);
			root.appendChild(CstmrCdtTrfInitnElement);
			document.appendChild(root);

			DOMSource domSource = new DOMSource(document);
			StreamResult streamResult = new StreamResult(file);
			TransformerFactory tf = TransformerFactory.newInstance();

			Transformer serializer = tf.newTransformer();
			serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			serializer.setOutputProperty(OutputKeys.INDENT, "yes");
			serializer.transform(domSource, streamResult);

			noLines = numberOfTransactions;
		} catch (Exception e) {
			err.append(e.toString());
			s_log.log(Level.SEVERE, "", e);
			return -1;
		}

		return noLines;
	} // exportToFile

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

		StringBuffer remittanceInformationSB = new StringBuffer();

		for (MPaySelectionLine mPaySelectionLine : mPaySelectionLines) {
			String documentNo = null;
			I_C_Invoice invoice = mPaySelectionLine.getC_Invoice();
			if (invoice != null) {
				if (remittanceInformationSB.length() == 0) {
					String referenceNo = invoice.getC_BPartner().getReferenceNo();
					if (!Util.isEmpty(referenceNo)) {
						remittanceInformationSB.append("/CNR/");
						remittanceInformationSB.append(referenceNo);
					}
				}
				documentNo = invoice.getDocumentNo();
				if (documentNo != null && documentNo.length() > 0) {
					remittanceInformationSB.append("/DOC/");
					remittanceInformationSB.append(documentNo);
					if (mPaySelectionLine.getDiscountAmt().doubleValue() <= -0.01) {
						remittanceInformationSB.append("/ ");
						remittanceInformationSB.append(mPaySelectionLine.getPayAmt());
					}
				}
			}
		}

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

	public void SetDocumentType(String paymentRule) {
		if (MPaySelectionCheck.PAYMENTRULE_DirectDebit.equals(paymentRule)) {
			documentType = SEPA_DIRECT_DEBIT;
			isDirectDebit = true;
		}
		else if (MPaySelectionCheck.PAYMENTRULE_DirectDeposit.equals(paymentRule)) {
			documentType = SEPA_CREDIT_TRANSFER;
			isDirectDebit = false;
		}
	}

	@Override
	public String getFilenamePrefix() {
		String creationDate = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(System.currentTimeMillis()) ;
		return "SEPA-Credit-Transfer-" + creationDate ;
	}

	@Override
	public String getFilenameSuffix() {
		return ".xml";
	}

	@Override
	public String getContentType() {
		return "text/xml";
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
		return isDirectDebit;
	}

	public void setDirectDebit(boolean isDirectDebit) {
		this.isDirectDebit = isDirectDebit;
	}

} // PaymentExport
