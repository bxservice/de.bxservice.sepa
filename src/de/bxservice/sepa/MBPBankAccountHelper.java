package de.bxservice.sepa;

import org.compiere.model.MBPBankAccount;

/**
 * I extended the table of {@link MBPBankAccount} but I did not want to exchange
 * the whole model classes. Thats why here are the things I added to this Model
 * for my own purposes.
 * 
 * @author tbayen
 */
public class MBPBankAccountHelper {

	/**
	 * german "Lastschriftart", has one of the scheme names as string.
	 */
	public static final String COLUMNNAME_SEPASDDSCHEME="SepaSddScheme";
	public static final String COLUMNNAME_ISTRANSFERRED="IsTransferred";
	public static final String COLUMNNAME_MNDTID="MndtId";
	public static final String COLUMNNAME_DATEDOC="DateDoc";
	public static final String COLUMNNAME_IBAN="IBAN";
}
