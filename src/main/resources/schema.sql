-- SmartKB 数据库结构 (H2 MySQL 模式 / MySQL 8 通用)

CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(128) NOT NULL,          -- SHA-256(salt + 明文)
    salt        VARCHAR(32)  NOT NULL,
    nickname    VARCHAR(64),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 知识库: 一个用户可建多个, 分块参数按库配置
CREATE TABLE IF NOT EXISTS knowledge_base (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    chunk_size    INT DEFAULT 500,              -- 分块目标字符数
    chunk_overlap INT DEFAULT 80,               -- 相邻块重叠字符数
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 文档: 状态机 PENDING -> PARSING -> INDEXED / FAILED
CREATE TABLE IF NOT EXISTS kb_document (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id       BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(256) NOT NULL,
    file_type   VARCHAR(16),
    file_size   BIGINT DEFAULT 0,
    status      VARCHAR(16) DEFAULT 'PENDING',
    chunk_count INT DEFAULT 0,
    char_count  INT DEFAULT 0,
    error_msg   VARCHAR(1024),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 文本分块: embedding 为 JSON 数组字符串(1024 维), 为空表示未向量化(仅关键词可检索)
CREATE TABLE IF NOT EXISTS doc_chunk (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    kb_id       BIGINT NOT NULL,
    chunk_index INT    NOT NULL,
    content     TEXT   NOT NULL,
    title_path  VARCHAR(512),                   -- 标题链路, 如 "线程池 > 核心参数"
    char_count  INT DEFAULT 0,
    embedding   TEXT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chunk_kb  ON doc_chunk (kb_id);
CREATE INDEX IF NOT EXISTS idx_chunk_doc ON doc_chunk (document_id);

CREATE TABLE IF NOT EXISTS conversation (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    kb_id      BIGINT NOT NULL,
    title      VARCHAR(256) DEFAULT '新对话',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_message (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id   BIGINT      NOT NULL,
    role              VARCHAR(16) NOT NULL,     -- user / assistant
    content           TEXT        NOT NULL,
    citations         TEXT,                     -- 引用片段 JSON(仅 assistant)
    rewritten_query   VARCHAR(1024),            -- 改写后的检索词(仅 assistant)
    prompt_tokens     INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_msg_conv ON chat_message (conversation_id);

-- AI 调用流水: 支撑用量统计页
CREATE TABLE IF NOT EXISTS ai_call_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT,
    call_type         VARCHAR(16) NOT NULL,     -- CHAT / EMBEDDING / REWRITE / RERANK
    model             VARCHAR(64),
    prompt_tokens     INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    total_tokens      INT DEFAULT 0,
    latency_ms        BIGINT DEFAULT 0,
    success           BOOLEAN DEFAULT TRUE,
    remark            VARCHAR(512),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
