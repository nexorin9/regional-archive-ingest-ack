-- =============================================================================
-- V3 · 双主键分离：区域平台 patient_ref 与院内 local_mrn 各占一列
--
--  为什么单独一列而不是复用 local_mrn：
--    区域健康档案平台给的 PATIENT_ID 与基层院区 HIS 的 localMrn 是两套独立编号体系。
--    早期版本把它们塞进同一列，于是「这条报文来自哪个居民」与「这位居民在本院是哪号」
--    变成同一个值 —— 换句话说，报文第一次到院时就凭空得到了一个院内主键，
--    而这个主键并没有经过任何院内系统的核对。等到确认台上有人点确认，
--    写回 HIS 的是这个凭空来的号，对端与本院从此对不上，且现场无从追溯是哪一步错的。
--
--  拆列之后的不变量：
--    1. patient_ref 只能由入站报文 PID-3 第一组件写入，本产品任何路径都不生成它；
--    2. local_mrn 只能由人工确认后的院内主索引写入，入账时恒为空串；
--    3. 两列各自非空时互不替代，写入路径（JdbcPatientKeyStore）拒绝静默覆盖已有值。
--
--  与 V1 的兼容：ALTER TABLE ADD COLUMN 带 NOT NULL DEFAULT ''，
--  因此存量行自动得到空 patient_ref，无需回填 —— 存量报文原文里带着 PID-3，
--  需要回查时仍可从 raw_payload 取（LedgerExporter 保持那条老路径）。
--  本脚本同样在 PostgreSQL 与 H2 PG 兼容模式下执行，不分叉。
-- =============================================================================

ALTER TABLE message_ledger ADD COLUMN patient_ref VARCHAR(64) NOT NULL DEFAULT '';

-- 「这位居民在本院建过档没有」是基层信息员的日频问句：
-- 平台每天问好几次「昨天那批居民档案推到你们那儿没有」，
-- 按区域主键一查就知道哪几条到了本院、哪几条还挂在对端。
CREATE INDEX idx_ledger_patient_ref ON message_ledger (patient_ref);

INSERT INTO schema_version (version, notes) VALUES
    ('V3', '双主键分离：message_ledger 新增 patient_ref 列（区域平台主键），与 local_mrn 互不替代');