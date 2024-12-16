package com.mirth.connect.server.migration;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.dbutils.DbUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mirth.connect.client.core.Version;
import com.mirth.connect.model.util.MigrationException;
import com.mirth.connect.server.util.DatabaseUtil;

public class Migrate4_3_0 extends Migrator implements ConfigurationMigrator {
	
	private Logger logger = LogManager.getLogger(getClass());

	@Override
	public Map<String, Object> getConfigurationPropertiesToAdd() {
		return null;
	}

	@Override
	public String[] getConfigurationPropertiesToRemove() {
		return null;
	}

    @SuppressWarnings("unchecked")
	@Override
    public void updateConfiguration(PropertiesConfiguration configuration) {
        if (getStartingVersion() == null || getStartingVersion().ordinal() < Version.v4_3_0.ordinal()) {
            updateConfiguration(configuration, "https.ciphersuites", OLD_DEFAULT_CIPHERSUITES, NEW_DEFAULT_CIPHERSUITES, CIPHERSUITES_TO_REMOVE);
            logger.error("In version 4.3.0, the following cipher suites have been disabled by default to reflect the lastest security best practices: TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384, TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384, TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384, TLS_DHE_DSS_WITH_AES_256_CBC_SHA256, TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA, TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA, TLS_ECDH_RSA_WITH_AES_256_CBC_SHA, TLS_DHE_DSS_WITH_AES_256_CBC_SHA, TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256, TLS_RSA_WITH_AES_128_CBC_SHA256, TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256, TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256, TLS_DHE_DSS_WITH_AES_128_CBC_SHA256, TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA, TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA, TLS_ECDH_RSA_WITH_AES_128_CBC_SHA, TLS_DHE_DSS_WITH_AES_128_CBC_SHA");

            updateSecurityConfiguration(configuration);
        }
    }

    @SuppressWarnings("unchecked")
	private void updateConfiguration(PropertiesConfiguration configuration, String key, String oldDefault, String newDefault, List<String> valuesToRemove) {
        String[] currentValue = configuration.getStringArray(key);
        boolean hasCustomValue = false;
        
        if (ArrayUtils.isNotEmpty(currentValue) && (currentValue.length > 1 || StringUtils.isNotBlank(currentValue[0]))) {
            String currentValueStr = StringUtils.join(currentValue, ',');

            // Only add .old property if the current value is not equal to the old or new defaults
            if (!StringUtils.equals(currentValueStr, newDefault) && !StringUtils.equals(currentValueStr, oldDefault)) {
            	hasCustomValue = true;
            	
                configuration.setProperty(key + ".old", currentValueStr);
                configuration.getLayout().setBlancLinesBefore(key + ".old", 1);
                configuration.getLayout().setComment(key + ".old", "In version 4.3.0 the default protocols / cipher suites were updated to reflect the latest security best practices. The old value for " + key + ", in case you need it, is below.\nIf you no longer need it, you can delete this property.");

                logger.error("In version 4.3.0 the default protocols / cipher suites were updated to reflect the latest security best practices. The old value for " + key + " is still present in mirth.properties in case you need it. If you no longer need it, you can delete this property.");
            }
        }

        if (hasCustomValue) {
        	// Remove weak protocols/ciphers from the user's custom values
        	Set<String> valueSet = new LinkedHashSet<String>();
            for (String value : currentValue) {
            	valueSet.addAll(Arrays.asList(StringUtils.split(value, ',')));
            }
            valueSet.removeAll(valuesToRemove);
            configuration.setProperty(key, StringUtils.join(valueSet, ','));
        } else {
        	configuration.setProperty(key, newDefault);
        }
    }

    void updateSecurityConfiguration(PropertiesConfiguration configuration) {
        if (getStartingVersion() == null || getStartingVersion().ordinal() < Version.v4_3_0.ordinal()) {
            /*
             * Set the encryption fallback algorithm to the previous setting, or the previous
             * default of "AES" if it was not present.
             */
            String encryptionAlgorithm = configuration.getString("encryption.algorithm");
            String encryptionFallbackAlgorithm = StringUtils.defaultIfBlank(encryptionAlgorithm, "AES");
            if (StringUtils.contains(encryptionFallbackAlgorithm, "/")) {
                /*
                 * If mode/padding was specified before, then remove it, because only the base
                 * algorithm was passed into the cipher in earlier versions.
                 */
                encryptionFallbackAlgorithm = StringUtils.defaultIfBlank(StringUtils.substring(encryptionFallbackAlgorithm, 0, StringUtils.indexOf(encryptionFallbackAlgorithm, '/')), "AES");
            }
            configuration.setProperty("encryption.fallback.algorithm", encryptionFallbackAlgorithm);
            configuration.getLayout().setBlancLinesBefore("encryption.fallback.algorithm", 1);
            configuration.getLayout().setComment("encryption.fallback.algorithm", "The algorithm to use when decrypting old message content.");

            /*
             * Set the encryption fallback charset to the JVM default if it's not UTF-8, as that was
             * the behavior in previous versions.
             */
            String defaultCharset = getDefaultCharset();
            if (!StringUtils.equals(defaultCharset, StandardCharsets.UTF_8.name())) {
                configuration.setProperty("encryption.fallback.charset", defaultCharset);
                configuration.getLayout().setBlancLinesBefore("encryption.fallback.charset", 1);
                configuration.getLayout().setComment("encryption.fallback.charset", "The character set encoding to use when decrypting old message content");
            }
        }
    }

    @Override
    public void migrate() throws MigrationException {
        try {
            if (scriptExists(getDatabaseType() + "-4.2.0-4.3.0-attachment-table.sql") && DatabaseUtil.tableExists(getConnection(), "D_CHANNELS")) {
                logger.debug("Migrating message attachment tables for " + getDatabaseType());

                PreparedStatement preparedStatement = null;
                ResultSet resultSet = null;

                try {
                    preparedStatement = getConnection().prepareStatement("SELECT LOCAL_CHANNEL_ID FROM D_CHANNELS");
                    resultSet = preparedStatement.executeQuery();

                    while (resultSet.next()) {
                        Map<String, Object> replacements = new HashMap<String, Object>();
                        replacements.put("localChannelId", resultSet.getLong(1));

                        logger.debug("Migrating message attachment table for local channel ID " + replacements.get("localChannelId"));
                        executeScript(getDatabaseType() + "-4.2.0-4.3.0-attachment-table.sql", replacements);
                    }
                } finally {
                    DbUtils.closeQuietly(resultSet);
                    DbUtils.closeQuietly(preparedStatement);
                }
            }
        } catch (Exception e) {
            throw new MigrationException("An error occurred while migrating message attachment tables.", e);
        }
    }

	@Override
	public void migrateSerializedData() throws MigrationException {

	}

	public Logger getLogger() {
		return logger;
	}

	public void setLogger(Logger logger) {
		this.logger = logger;
	}

    // Making class mockable
    String getDefaultCharset() {
        return Charset.defaultCharset().name();
    }

	protected static String OLD_DEFAULT_CIPHERSUITES = "TLS_CHACHA20_POLY1305_SHA256,TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256,TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,TLS_RSA_WITH_AES_256_GCM_SHA384,TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384,TLS_DHE_RSA_WITH_AES_256_GCM_SHA384,TLS_DHE_DSS_WITH_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256,TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,TLS_DHE_DSS_WITH_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384,TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384,TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384,TLS_DHE_DSS_WITH_AES_256_CBC_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA,TLS_ECDH_RSA_WITH_AES_256_CBC_SHA,TLS_DHE_DSS_WITH_AES_256_CBC_SHA,TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,TLS_RSA_WITH_AES_128_CBC_SHA256,TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256,TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256,TLS_DHE_DSS_WITH_AES_128_CBC_SHA256,TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA,TLS_ECDH_RSA_WITH_AES_128_CBC_SHA,TLS_DHE_DSS_WITH_AES_128_CBC_SHA,TLS_EMPTY_RENEGOTIATION_INFO_SCSV";
	protected static String NEW_DEFAULT_CIPHERSUITES = "TLS_CHACHA20_POLY1305_SHA256,TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256,TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,TLS_RSA_WITH_AES_256_GCM_SHA384,TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384,TLS_DHE_RSA_WITH_AES_256_GCM_SHA384,TLS_DHE_DSS_WITH_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256,TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,TLS_DHE_DSS_WITH_AES_128_GCM_SHA256,TLS_EMPTY_RENEGOTIATION_INFO_SCSV";
	@SuppressWarnings("unchecked")
	protected static List<String> CIPHERSUITES_TO_REMOVE = Arrays.asList(new String[] {
	        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384", "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384", "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384",
	        "TLS_DHE_DSS_WITH_AES_256_CBC_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA", "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA", 
	        "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA", "TLS_DHE_DSS_WITH_AES_256_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", 
	        "TLS_RSA_WITH_AES_128_CBC_SHA256", "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256", "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256", 
	        "TLS_DHE_DSS_WITH_AES_128_CBC_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA", 
	        "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA", "TLS_DHE_DSS_WITH_AES_128_CBC_SHA" });
}
