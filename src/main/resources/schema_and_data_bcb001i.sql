-- ==========================================================
-- DDL: BCB001I 테이블 생성 (BCB001I 모델 연동)
-- ==========================================================
CREATE TABLE IF NOT EXISTS BCB001I (
    id VARCHAR(50) PRIMARY KEY COMMENT '세션 ID (고유키)',
    name VARCHAR(100) COMMENT '사용자 이름',
    age INT COMMENT '나이'
) COMMENT 'BCB001I 관리 테이블';

-- ==========================================================
-- DML: 샘플 데이터 30건 적재
-- ==========================================================
-- 기존 데이터 충돌 방지를 원할 경우, TRUNCATE 등을 추가할 수 있습니다.
-- TRUNCATE TABLE BCB001I;

INSERT INTO
    BCB001I (id, name, age)
VALUES ('SESSION-001', 'Alice', 25),
    ('SESSION-002', 'Bob', 32),
    ('SESSION-003', 'Charlie', 28),
    ('SESSION-004', 'David', 45),
    ('SESSION-005', 'Eve', 22),
    ('SESSION-006', 'Frank', 34),
    ('SESSION-007', 'Grace', 29),
    ('SESSION-008', 'Heidi', 41),
    ('SESSION-009', 'Ivan', 26),
    ('SESSION-010', 'Judy', 38),
    ('SESSION-011', 'Kevin', 31),
    ('SESSION-012', 'Leo', 44),
    ('SESSION-013', 'Mallory', 27),
    ('SESSION-014', 'Niaj', 23),
    ('SESSION-015', 'Olivia', 35),
    ('SESSION-016', 'Peggy', 50),
    ('SESSION-017', 'Quentin', 21),
    ('SESSION-018', 'Rupert', 47),
    ('SESSION-019', 'Sybil', 30),
    ('SESSION-020', 'Trent', 33),
    ('SESSION-021', 'Uma', 24),
    ('SESSION-022', 'Victor', 39),
    ('SESSION-023', 'Walter', 42),
    ('SESSION-024', 'Xenia', 28),
    ('SESSION-025', 'Yvonne', 37),
    ('SESSION-026', 'Zelda', 40),
    ('SESSION-027', 'Arthur', 48),
    ('SESSION-028', 'Betty', 29),
    ('SESSION-029', 'Celine', 31),
    ('SESSION-030', 'Derek', 36);