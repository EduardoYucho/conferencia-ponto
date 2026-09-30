-- =============================================================================
-- V5 - Armazenamento de comprovantes (PDF) e controle de acesso (RBAC)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- tb_comprovante: PDFs arquivados, vinculados ao dia (1 dia : N comprovantes).
-- O binário NÃO fica no banco: caminho_arquivo aponta para o armazenamento
-- gerenciado pela aplicação (arquivo renomeado para comprovante_<uuid>.pdf).
-- -----------------------------------------------------------------------------
CREATE TABLE tb_comprovante (
    id                   UUID          NOT NULL DEFAULT gen_random_uuid(),
    registro_jornada_id  UUID          NOT NULL,
    caminho_arquivo      VARCHAR(500)  NOT NULL,
    tipo_batida          VARCHAR(10)   NOT NULL,
    data_upload          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    data_hora_batida     TIMESTAMP(0)  NOT NULL,
    nome_original        VARCHAR(255)  NOT NULL,
    hash_sha256          VARCHAR(64)   NOT NULL,
    tamanho_bytes        BIGINT        NOT NULL,

    CONSTRAINT pk_comprovante PRIMARY KEY (id),
    -- RESTRICT: um dia com comprovante arquivado não pode ser apagado (trilha de auditoria)
    CONSTRAINT fk_comprovante_registro_jornada
        FOREIGN KEY (registro_jornada_id) REFERENCES tb_registro_jornada (id) ON DELETE RESTRICT,
    CONSTRAINT uk_comprovante_caminho UNIQUE (caminho_arquivo),
    CONSTRAINT uk_comprovante_hash UNIQUE (hash_sha256),
    CONSTRAINT ck_comprovante_tipo_batida
        CHECK (tipo_batida IN ('ENTRADA_1', 'SAIDA_1', 'ENTRADA_2', 'SAIDA_2')),
    CONSTRAINT ck_comprovante_tamanho CHECK (tamanho_bytes > 0),
    -- Um comprovante por batida. DEFERRABLE: quando um PDF chega fora de ordem as
    -- posições são reorganizadas dentro da mesma transação e só conferidas no commit.
    CONSTRAINT uk_comprovante_registro_tipo
        UNIQUE (registro_jornada_id, tipo_batida) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ix_comprovante_registro_jornada ON tb_comprovante (registro_jornada_id);

COMMENT ON TABLE  tb_comprovante IS 'Comprovantes de ponto arquivados (PDF fora do banco), 1:N com tb_registro_jornada.';
COMMENT ON COLUMN tb_comprovante.caminho_arquivo IS 'Caminho relativo à raiz do armazenamento, ex.: 2026/09/comprovante_<uuid>.pdf';
COMMENT ON COLUMN tb_comprovante.hash_sha256 IS 'Hash do conteúdo, conferido a cada download (integridade).';

-- -----------------------------------------------------------------------------
-- Usuários e perfis (RBAC)
-- -----------------------------------------------------------------------------
CREATE TABLE tb_role (
    id         SMALLINT      NOT NULL,
    nome       VARCHAR(30)   NOT NULL,
    descricao  VARCHAR(200)  NOT NULL,

    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT uk_role_nome UNIQUE (nome),
    CONSTRAINT ck_role_nome CHECK (nome LIKE 'ROLE\_%')
);

INSERT INTO tb_role (id, nome, descricao) VALUES
    (1, 'ROLE_ADMIN',  'Leitura e escrita total, lançamentos manuais e processamento de arquivos'),
    (2, 'ROLE_USER',   'Leitura e escrita total, lançamentos manuais e processamento de arquivos'),
    (3, 'ROLE_VIEWER', 'Somente leitura (auditoria/coordenação)');

CREATE TABLE tb_usuario (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    login            VARCHAR(60)   NOT NULL,
    nome             VARCHAR(120)  NOT NULL,
    senha_hash       VARCHAR(100)  NOT NULL,
    ativo            BOOLEAN       NOT NULL DEFAULT TRUE,
    criado_em        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    ultimo_login_em  TIMESTAMPTZ,

    CONSTRAINT pk_usuario PRIMARY KEY (id),
    CONSTRAINT uk_usuario_login UNIQUE (login),
    CONSTRAINT ck_usuario_login CHECK (login = lower(login) AND length(login) >= 3)
);

CREATE TABLE tb_usuario_role (
    usuario_id  UUID      NOT NULL,
    role_id     SMALLINT  NOT NULL,

    CONSTRAINT pk_usuario_role PRIMARY KEY (usuario_id, role_id),
    CONSTRAINT fk_usuario_role_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id) ON DELETE CASCADE,
    CONSTRAINT fk_usuario_role_role    FOREIGN KEY (role_id)    REFERENCES tb_role (id)
);

COMMENT ON TABLE tb_usuario IS 'Usuários do portal. Senha armazenada apenas como hash BCrypt.';
