-- =============================================================================
-- V4 - Auditoria da importação automática de comprovantes (PDF)
--
--  * hash_sha256 único: o mesmo arquivo nunca é processado duas vezes
--    (reinício do serviço, evento duplicado do sistema de arquivos).
--  * Índice único parcial: uma data/hora só pode gerar UMA batida importada,
--    mesmo que o comprovante seja baixado de novo com outro nome/conteúdo.
-- =============================================================================

CREATE TABLE tb_comprovante_ponto (
    id                UUID          NOT NULL DEFAULT gen_random_uuid(),
    nome_arquivo      VARCHAR(255)  NOT NULL,
    hash_sha256       VARCHAR(64)   NOT NULL,
    data_hora_batida  TIMESTAMP(0),
    status            VARCHAR(20)   NOT NULL,
    mensagem          VARCHAR(500),
    processado_em     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_comprovante_ponto PRIMARY KEY (id),
    CONSTRAINT uk_comprovante_ponto_hash UNIQUE (hash_sha256),
    CONSTRAINT ck_comprovante_ponto_status
        CHECK (status IN ('IMPORTADO', 'DUPLICADO', 'REJEITADO', 'INVALIDO')),
    CONSTRAINT ck_comprovante_ponto_data_hora
        CHECK (status = 'INVALIDO' OR data_hora_batida IS NOT NULL)
);

CREATE UNIQUE INDEX ux_comprovante_ponto_batida_importada
    ON tb_comprovante_ponto (data_hora_batida)
    WHERE status = 'IMPORTADO';

CREATE INDEX ix_comprovante_ponto_processado_em
    ON tb_comprovante_ponto (processado_em DESC);

COMMENT ON TABLE tb_comprovante_ponto IS 'PDFs de comprovante processados pelo monitor de diretório (auditoria e deduplicação).';
