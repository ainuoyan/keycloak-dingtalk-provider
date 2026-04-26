package com.tencent.keycloak.dingtalk;

import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

final class DingTalkNumericUserCleanup {

    static final String CONFIRM = "DELETE_NUMERIC_DINGTALK_USERS";

    private static final Logger logger = Logger.getLogger(DingTalkNumericUserCleanup.class);

    private DingTalkNumericUserCleanup() {
    }

    static CleanupResult preview(KeycloakSession session, RealmModel realm, IdentityProviderModel idp) {
        List<String> usernames = candidateUsernames(session, realm, idp);
        return new CleanupResult(idp.getAlias(), true, usernames.size(), 0, usernames);
    }

    static CleanupResult execute(KeycloakSession session, RealmModel realm, IdentityProviderModel idp) {
        List<UserModel> candidates = findCandidates(session, realm, idp);
        List<String> usernames = sortedUsernames(candidates);

        int deleted = 0;
        for (UserModel user : candidates) {
            logger.warnf("Deleting numeric DingTalk-managed user. realm=%s, idp=%s, username=%s",
                    realm.getName(), idp.getAlias(), user.getUsername());
            if (session.users().removeUser(realm, user)) {
                deleted++;
            }
        }

        return new CleanupResult(idp.getAlias(), false, usernames.size(), deleted, usernames);
    }

    private static List<String> candidateUsernames(KeycloakSession session, RealmModel realm, IdentityProviderModel idp) {
        return sortedUsernames(findCandidates(session, realm, idp));
    }

    private static List<UserModel> findCandidates(KeycloakSession session, RealmModel realm, IdentityProviderModel idp) {
        return session.users()
                .searchForUserByUserAttributeStream(realm, "dingtalk_idp_alias", idp.getAlias())
                .filter(user -> user.getUsername() != null && user.getUsername().matches("\\d+"))
                .filter(user -> "true".equals(user.getFirstAttribute("dingtalk_managed")))
                .filter(DingTalkNumericUserCleanup::isSyncCreatedOrLegacyNumericUser)
                .filter(user -> hasFederatedIdentity(session, realm, user, idp.getAlias()))
                .toList();
    }

    private static List<String> sortedUsernames(List<UserModel> users) {
        return users.stream()
                .map(UserModel::getUsername)
                .sorted()
                .toList();
    }

    private static boolean isSyncCreatedOrLegacyNumericUser(UserModel user) {
        if ("true".equals(user.getFirstAttribute(DingTalkUserSyncTask.DINGTALK_CREATED_BY_SYNC))) {
            return true;
        }

        String username = user.getUsername();
        String dingtalkUserId = user.getFirstAttribute("dingtalk_userid");
        return StringUtils.isNotBlank(username) && username.equals(dingtalkUserId);
    }

    private static boolean hasFederatedIdentity(KeycloakSession session, RealmModel realm, UserModel user, String alias) {
        FederatedIdentityModel identity = session.users().getFederatedIdentity(realm, user, alias);
        return identity != null;
    }

    record CleanupResult(String alias, boolean dryRun, int candidateCount, int deleted, List<String> usernames) {
    }
}
