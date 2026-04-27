package com.ainuoyan.keycloak.dingtalk;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

final class DingTalkCreatedUserInitializer {

    static final String INITIALIZE_CREATED_USERS = "periodicSyncInitializeCreatedUsers";
    static final int TEMPORARY_PASSWORD_LENGTH = 24;
    static final String DINGTALK_FIRST_NAME = "dingtalk_first_name";
    static final String DINGTALK_LAST_NAME = "dingtalk_last_name";

    private static final Logger logger = Logger.getLogger(DingTalkCreatedUserInitializer.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String SPECIAL = "!@#$%^&*()-_=+[]{}:,.?";
    private static final String ALL = UPPER + LOWER + DIGIT + SPECIAL;
    private static final String NICK_NAME = "nickname";

    private DingTalkCreatedUserInitializer() {
    }

    static boolean isEnabled(IdentityProviderModel idp) {
        return idp != null && isEnabled(idp.getConfig());
    }

    static boolean isEnabled(Map<String, String> config) {
        return config != null
                && Boolean.parseBoolean(config.getOrDefault(INITIALIZE_CREATED_USERS, "false"));
    }

    static void initializeAfterCommit(KeycloakSession session, RealmModel realm, IdentityProviderModel idp,
                                      String username) {
        if (!isEnabled(idp) || session == null || realm == null || StringUtils.isBlank(username)) {
            return;
        }

        String realmId = realm.getId();
        String realmName = realm.getName();
        String idpAlias = idp.getAlias();
        String sanitizedUsername = StringUtils.trim(username);
        KeycloakSessionFactory sessionFactory = session.getKeycloakSessionFactory();
        KeycloakContext sourceContext = session.getContext();
        KeycloakTransactionManager transactionManager = session.getTransactionManager();
        if (transactionManager == null || !transactionManager.isActive()) {
            initializeInNewTransaction(sessionFactory, sourceContext, realmId, realmName, idpAlias, sanitizedUsername);
            return;
        }

        transactionManager.enlistAfterCompletion(new AbstractKeycloakTransaction() {
            @Override
            protected void commitImpl() {
                initializeInNewTransaction(sessionFactory, sourceContext, realmId, realmName, idpAlias,
                        sanitizedUsername);
            }

            @Override
            protected void rollbackImpl() {
                logger.warnf("Skip DingTalk created user initialization because transaction rolled back. realm=%s, idp=%s, username=%s",
                        realmName, idpAlias, sanitizedUsername);
            }
        });
    }

    private static void initializeInNewTransaction(KeycloakSessionFactory sessionFactory, KeycloakContext sourceContext,
                                                   String realmId, String realmName, String idpAlias, String username) {
        if (sessionFactory == null || StringUtils.isBlank(realmId)) {
            logger.warnf("Skip DingTalk created user initialization because session factory or realm is unavailable. realm=%s, idp=%s, username=%s",
                    realmName, idpAlias, username);
            return;
        }

        AtomicReference<InitializationResult> initialization = new AtomicReference<>();
        try {
            KeycloakModelUtils.runJobInTransaction(sessionFactory, sourceContext, initSession -> {
                RealmModel currentRealm = DingTalkUserSyncTask.resolveRealmAndBindContext(initSession, realmId);
                if (currentRealm == null) {
                    logger.warnf("Skip DingTalk created user initialization because realm cannot be resolved. realm=%s, idp=%s, username=%s",
                            realmName, idpAlias, username);
                    return;
                }
                initialization.set(initializeCreatedUser(initSession, currentRealm, idpAlias, username));
            });
        } catch (Exception e) {
            logger.warnf(e, "Failed to initialize DingTalk created user after commit. realm=%s, idp=%s, username=%s",
                    realmName, idpAlias, username);
            return;
        }

        InitializationResult result = initialization.get();
        if (result == null || !result.success()) {
            return;
        }

        try {
            KeycloakModelUtils.runJobInTransaction(sessionFactory, sourceContext, initSession -> {
                RealmModel currentRealm = DingTalkUserSyncTask.resolveRealmAndBindContext(initSession, realmId);
                if (currentRealm == null) {
                    logger.warnf("Skip DingTalk created user local name split because realm cannot be resolved. realm=%s, idp=%s, username=%s",
                            realmName, idpAlias, username);
                    return;
                }
                storeInitializedUserNameParts(initSession, currentRealm, idpAlias, username);
            });
        } catch (Exception e) {
            logger.warnf(e, "Failed to store DingTalk created user local name parts after initialization. realm=%s, idp=%s, username=%s",
                    realmName, idpAlias, username);
        }
    }

    static InitializationResult initializeCreatedUser(KeycloakSession session, RealmModel realm,
                                                      String idpAlias, String username) {
        UserModel user = session.users().getUserByUsername(realm, username);
        if (user == null) {
            logger.warnf("Skip DingTalk created user initialization because user is not searchable. realm=%s, idp=%s, username=%s",
                    realm.getName(), idpAlias, username);
            return InitializationResult.failed(username, "user_not_found");
        }

        String temporaryPassword = generateTemporaryPassword();
        return initializeExistingUser(realm, idpAlias, user, temporaryPassword);
    }

    static InitializationResult initializeExistingUser(RealmModel realm, String idpAlias, UserModel user,
                                                       String temporaryPassword) {
        String username = user == null ? "" : user.getUsername();
        if (user == null || StringUtils.isBlank(temporaryPassword)) {
            return InitializationResult.failed(username, "invalid_user_or_password");
        }

        try {
            boolean credentialUpdated = user.credentialManager()
                    .updateCredential(UserCredentialModel.password(temporaryPassword, false));
            if (!credentialUpdated) {
                logger.warnf("DingTalk created user temporary password was rejected. realm=%s, idp=%s, username=%s",
                        realmName(realm), idpAlias, username);
                return InitializationResult.failed(username, "credential_rejected");
            }

            user.addRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD);
            user.setEnabled(true);
            logger.infof("Initialized DingTalk created user. realm=%s, idp=%s, username=%s, credential=temporary-password, requiredAction=UPDATE_PASSWORD, enabled=true",
                    realmName(realm), idpAlias, username);
            return InitializationResult.success(username);
        } catch (Exception e) {
            logger.warnf(e, "Failed to initialize DingTalk created user. realm=%s, idp=%s, username=%s",
                    realmName(realm), idpAlias, username);
            return InitializationResult.failed(username, e.getClass().getSimpleName());
        }
    }

    static boolean storeInitializedUserNameParts(KeycloakSession session, RealmModel realm,
                                                 String idpAlias, String username) {
        UserModel user = session.users().getUserByUsername(realm, username);
        if (user == null) {
            logger.warnf("Skip DingTalk created user local name split because user is not searchable. realm=%s, idp=%s, username=%s",
                    realmName(realm), idpAlias, username);
            return false;
        }
        return applyPostActivationNameMetadata(realm, idpAlias, user);
    }

    static boolean applyPostActivationNameMetadata(RealmModel realm, String idpAlias, UserModel user) {
        String username = user == null ? "" : user.getUsername();
        if (user == null) {
            return false;
        }

        NameParts nameParts = resolveNameParts(user);
        if (!nameParts.hasBoth()) {
            return false;
        }

        try {
            user.setSingleAttribute(DINGTALK_FIRST_NAME, nameParts.firstName());
            user.setSingleAttribute(DINGTALK_LAST_NAME, nameParts.lastName());
            logger.infof("Stored DingTalk created user local name parts after initialization. realm=%s, idp=%s, username=%s, attributes=[%s,%s]",
                    realmName(realm), idpAlias, username, DINGTALK_FIRST_NAME, DINGTALK_LAST_NAME);
            return true;
        } catch (Exception e) {
            logger.warnf(e, "Failed to store DingTalk created user local name parts. realm=%s, idp=%s, username=%s",
                    realmName(realm), idpAlias, username);
            return false;
        }
    }

    private static NameParts resolveNameParts(UserModel user) {
        String[] candidates = {
                user.getFirstName(),
                user.getFirstAttribute(NICK_NAME)
        };
        for (String candidate : candidates) {
            NameParts nameParts = splitChineseDisplayName(candidate);
            if (nameParts.hasBoth()) {
                return nameParts;
            }
        }
        return NameParts.empty();
    }

    static NameParts splitChineseDisplayName(String displayName) {
        String compactName = removeWhitespace(displayName);
        if (StringUtils.isBlank(compactName)) {
            return NameParts.empty();
        }
        int codePointCount = compactName.codePointCount(0, compactName.length());
        if (codePointCount < 2 || !compactName.codePoints().allMatch(DingTalkCreatedUserInitializer::isHanCodePoint)) {
            return NameParts.empty();
        }
        int firstNameEnd = compactName.offsetByCodePoints(0, 1);
        return new NameParts(compactName.substring(0, firstNameEnd), compactName.substring(firstNameEnd));
    }

    private static String removeWhitespace(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder compact = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .forEach(compact::appendCodePoint);
        return compact.toString();
    }

    private static boolean isHanCodePoint(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    static String generateTemporaryPassword() {
        return generateTemporaryPassword(TEMPORARY_PASSWORD_LENGTH);
    }

    static String generateTemporaryPassword(int length) {
        int effectiveLength = Math.max(length, 16);
        List<Character> chars = new ArrayList<>(effectiveLength);
        chars.add(randomChar(UPPER));
        chars.add(randomChar(LOWER));
        chars.add(randomChar(DIGIT));
        chars.add(randomChar(SPECIAL));
        while (chars.size() < effectiveLength) {
            chars.add(randomChar(ALL));
        }
        Collections.shuffle(chars, RANDOM);
        StringBuilder password = new StringBuilder(effectiveLength);
        for (Character c : chars) {
            password.append(c.charValue());
        }
        return password.toString();
    }

    private static char randomChar(String candidates) {
        return candidates.charAt(RANDOM.nextInt(candidates.length()));
    }

    private static String realmName(RealmModel realm) {
        return realm == null ? "" : realm.getName();
    }

    record InitializationResult(String username, boolean success, String reason) {
        static InitializationResult success(String username) {
            return new InitializationResult(username, true, "");
        }

        static InitializationResult failed(String username, String reason) {
            return new InitializationResult(username, false, StringUtils.defaultString(reason));
        }
    }

    record NameParts(String firstName, String lastName) {
        static NameParts empty() {
            return new NameParts(null, null);
        }

        boolean hasBoth() {
            return StringUtils.isNotBlank(firstName) && StringUtils.isNotBlank(lastName);
        }
    }
}
