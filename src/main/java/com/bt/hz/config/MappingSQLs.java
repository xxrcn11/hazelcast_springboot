package com.bt.hz.config;

import java.util.List;

/**
 * Hazelcast SQL (Jet 엔진) 환경에서 IMap을 RDBMS 구조처럼 쿼리하거나,
 * 외부 DB 연동(MapStore)을 위한 매핑을 정의할 때 사용하는 CREATE MAPPING SQL 모음입니다.
 */
public final class MappingSQLs {

        private MappingSQLs() {
        }

        // ==========================================
        // Sessions 도메인 매핑
        // ==========================================
        public static final String CREATE_MAPPING_SESSION_BCB001I_IMAP = "CREATE OR REPLACE MAPPING BCB001I (" +
                        "    __key VARCHAR," +
                        "    id VARCHAR," +
                        "    name VARCHAR," +
                        "    age INTEGER" +
                        ") " +
                        "TYPE IMap " +
                        "OPTIONS (" +
                        "    'keyFormat' = 'java'," +
                        "    'keyJavaClass' = 'java.lang.String'," +
                        "    'valueFormat' = 'java'," +
                        "    'valueJavaClass' = 'com.bt.hz.domain.sessions.models.BCB001I'" +
                        ")";

        public static final String CREATE_MAPPING_SESSION_M_SYSSE001I_IMAP = "CREATE OR REPLACE MAPPING M_SYSSE001I (" +
                        "    __key VARCHAR," +
                        "    LOGIN VARCHAR EXTERNAL NAME \"login\"," +
                        "    LOGIN_TYPE VARCHAR EXTERNAL NAME \"loginType\"," +
                        "    USER_INFO VARCHAR EXTERNAL NAME \"userInfo\"," +
                        "    SESSION_ID VARCHAR EXTERNAL NAME \"sessionId\"" +
                        ") " +
                        "TYPE IMap " +
                        "OPTIONS (" +
                        "    'keyFormat' = 'java'," +
                        "    'keyJavaClass' = 'java.lang.String'," +
                        "    'valueFormat' = 'java'," +
                        "    'valueJavaClass' = 'com.bt.hz.domain.sessions.models.SYSSE001I'" +
                        ")";

        public static final String CREATE_MAPPING_SESSION_M_SYSSE002I_IMAP = "CREATE OR REPLACE MAPPING M_SYSSE002I (" +
                        "    __key VARCHAR," +
                        "    userId VARCHAR," +
                        "    userName VARCHAR," +
                        "    role VARCHAR," +
                        "    num BIGINT," +
                        "    age INTEGER," +
                        "    loginAt VARCHAR" +
                        ") " +
                        "TYPE IMap " +
                        "OPTIONS (" +
                        "    'keyFormat' = 'java'," +
                        "    'keyJavaClass' = 'java.lang.String'," +
                        "    'valueFormat' = 'java'," +
                        "    'valueJavaClass' = 'com.bt.hz.domain.sessions.models.SessionDto'" +
                        ")";

        public static final String CREATE_MAPPING_SESSION_M_SYSSE014I_IMAP = "CREATE OR REPLACE MAPPING M_SYSSE014I (" +
                        "    __key VARCHAR," +
                        "    userId VARCHAR," +
                        "    userName VARCHAR," +
                        "    role VARCHAR," +
                        "    num BIGINT," +
                        "    age INTEGER," +
                        "    loginAt VARCHAR," +
                        "    loginType VARCHAR," +
                        "    logoutAt VARCHAR" +
                        ") " +
                        "TYPE IMap " +
                        "OPTIONS (" +
                        "    'keyFormat' = 'java'," +
                        "    'keyJavaClass' = 'java.lang.String'," +
                        "    'valueFormat' = 'java'," +
                        "    'valueJavaClass' = 'com.bt.hz.domain.sessions.models.SYSSE014I'" +
                        ")";

        public static final String CREATE_MAPPING_SESSION_M_SYSSE015I_IMAP = "CREATE OR REPLACE MAPPING M_SYSSE015I (" +
                        "    __key VARCHAR," +
                        "    stdYmd VARCHAR," +
                        "    stdHour VARCHAR," +
                        "    cnt INTEGER" +
                        ") " +
                        "TYPE IMap " +
                        "OPTIONS (" +
                        "    'keyFormat' = 'java'," +
                        "    'keyJavaClass' = 'java.lang.String'," +
                        "    'valueFormat' = 'java'," +
                        "    'valueJavaClass' = 'com.bt.hz.domain.sessions.models.SYSSE015I'" +
                        ")";

        // ==========================================
        // Swift 도메인 매핑 (예시)
        // ==========================================
        // public static final String CREATE_MAPPING_SWIFT_MESSAGE = "...";

        // ==========================================
        // Topics 도메인 매핑 (예시)
        // ==========================================
        // public static final String CREATE_MAPPING_TOPICS_SUBSCRIBER = "...";

        /**
         * 애플리케이션 시작 시 등록해야 할 모든 CREATE MAPPING 구문들의 리스트를 반환합니다.
         * 외부(SpringBoot Bootstrap Listner 등)에서 이 메서드를 호출하여 Loop를 돌며 초기화할 수 있습니다.
         */
        public static List<String> getAllMappings() {
                return List.of(
                                // 여기에 등록할 매핑 상수들을 나열합니다.
                                CREATE_MAPPING_SESSION_BCB001I_IMAP,
                                CREATE_MAPPING_SESSION_M_SYSSE001I_IMAP,
                                CREATE_MAPPING_SESSION_M_SYSSE002I_IMAP,
                                CREATE_MAPPING_SESSION_M_SYSSE014I_IMAP,
                                CREATE_MAPPING_SESSION_M_SYSSE015I_IMAP);
        }
}
