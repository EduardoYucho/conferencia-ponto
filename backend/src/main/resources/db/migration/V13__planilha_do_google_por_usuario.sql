-- =============================================================================
-- V13 - Planilha de conferência no Google Sheets
--
-- Cada usuário pode indicar uma planilha do Google (compartilhada com a conta de
-- serviço do sistema) que passa a ser reescrita a cada mudança no ponto dele.
-- A chave da conta de serviço NÃO fica no banco: é um arquivo no computador do
-- servidor (ponto.google.credencial).
-- =============================================================================

CREATE TABLE tb_planilha_google (
    usuario_id      UUID          NOT NULL,
    planilha_id     VARCHAR(120)  NOT NULL,
    titulo          VARCHAR(300),
    vinculada_em    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    vinculada_por   VARCHAR(60),
    sincronizada_em TIMESTAMPTZ,
    erro            VARCHAR(600),

    CONSTRAINT pk_planilha_google PRIMARY KEY (usuario_id),
    CONSTRAINT fk_planilha_google_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id) ON DELETE CASCADE,
    CONSTRAINT uk_planilha_google_planilha UNIQUE (planilha_id)
);

COMMENT ON TABLE tb_planilha_google IS 'Planilha do Google Sheets em que a conferência de ponto de cada usuário é publicada.';
COMMENT ON COLUMN tb_planilha_google.planilha_id IS 'Identificador da planilha (trecho do link depois de /d/).';
COMMENT ON COLUMN tb_planilha_google.erro IS 'Motivo da última falha ao gravar; nulo quando a última gravação deu certo.';
