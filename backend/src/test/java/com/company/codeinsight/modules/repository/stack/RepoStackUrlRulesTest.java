package com.company.codeinsight.modules.repository.stack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoStackUrlRulesTest {

    @Test
    void detectsDbSuffixFromUrl() {
        assertTrue(RepoStackUrlRules.isDbByUrl("https://code.paic.com.cn/git/ph_rmwp_core_db.git"));
        assertTrue(RepoStackUrlRules.isDbByUrl("https://example.com/foo/bar-db"));
        assertTrue(RepoStackUrlRules.isDbByUrl("https://example.com/foo/BAR_DB.GIT"));
        assertFalse(RepoStackUrlRules.isDbByUrl("https://example.com/foo/ph_rmwp_core.git"));
        assertFalse(RepoStackUrlRules.isDbByUrl("https://example.com/foo/db_utils.git"));
        assertFalse(RepoStackUrlRules.isDbByUrl(""));
        assertFalse(RepoStackUrlRules.isDbByUrl(null));
    }

    @Test
    void extractsRepoName() {
        assertEquals("ph_rmwp_core_db",
                RepoStackUrlRules.extractRepoName("https://code.paic.com.cn/git/ph_rmwp_core_db.git").orElse(null));
        assertEquals("bar-db",
                RepoStackUrlRules.extractRepoName("https://example.com/foo/bar-db/").orElse(null));
    }

    @Test
    void guessesDialectFromName() {
        assertEquals("MySQL",
                RepoStackUrlRules.guessDbTechStack("https://h/x/order_mysql_db.git").orElse(null));
        assertEquals("PostgreSQL",
                RepoStackUrlRules.guessDbTechStack("https://h/x/app_pg_db.git").orElse(null));
        assertTrue(RepoStackUrlRules.guessDbTechStack("https://h/x/ph_rmwp_core_db.git").isEmpty());
    }
}
