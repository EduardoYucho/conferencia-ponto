-- =============================================================================
-- V15 - Módulo base de conhecimento
--
-- * Schema "conhecimento", separado do ponto e do módulo de atendimentos: os registros não
--   dependem do atendimento que os gerou (ele é apagado pela retenção; o registro fica).
-- * Busca: tsvector em português sem acento (mantido por gatilho: unaccent não pode entrar
--   numa coluna gerada) + trigramas para código de tela, mensagem de erro e cliente.
-- * unaccent e pg_trgm são extensões "trusted": o dono do banco cria sem superusuário.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS unaccent SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public;

CREATE SCHEMA conhecimento;
COMMENT ON SCHEMA conhecimento IS 'Módulo base de conhecimento: registros dos atendimentos confirmados, para pesquisa.';

-- Sem acento, com o dicionário explícito: assim a função é imutável e pode entrar em índice.
CREATE FUNCTION conhecimento.sem_acento(texto TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN public.unaccent('public.unaccent'::regdictionary, texto);

-- ----------------------------------------------------------------------- acesso
CREATE TABLE conhecimento.acesso (
    usuario_id     UUID          NOT NULL,
    pesquisar      BOOLEAN       NOT NULL DEFAULT FALSE,
    curar          BOOLEAN       NOT NULL DEFAULT FALSE,
    concedido_por  VARCHAR(60),
    atualizado_em  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_con_acesso PRIMARY KEY (usuario_id),
    CONSTRAINT fk_con_acesso_usuario FOREIGN KEY (usuario_id) REFERENCES public.tb_usuario (id) ON DELETE CASCADE,
    CONSTRAINT ck_con_acesso_curar CHECK (pesquisar OR NOT curar)
);
COMMENT ON TABLE conhecimento.acesso IS 'Quem pesquisa na base e quem cura (edita qualquer registro e muda a situação). Curar exige pesquisar.';

-- --------------------------------------------------------------------- registro
CREATE TABLE conhecimento.registro (
    id                UUID          NOT NULL DEFAULT gen_random_uuid(),
    origem            VARCHAR(10)   NOT NULL DEFAULT 'gerador',
    atendimento_id    UUID,
    saida_id          UUID,
    autor_id          UUID          NOT NULL,
    tipo              VARCHAR(10)   NOT NULL,
    titulo            VARCHAR(200)  NOT NULL,
    cliente           VARCHAR(150),
    contato           VARCHAR(150),
    modulo            VARCHAR(300),
    tela_nome         VARCHAR(200),
    tela_codigo       VARCHAR(40),
    sintoma           TEXT,
    mensagem_erro     TEXT,
    causa             TEXT,
    resolucao_texto   TEXT,
    resolucao_html    TEXT,
    situacao          VARCHAR(24)   NOT NULL,
    chamado_dev       VARCHAR(60),
    versao_correcao   VARCHAR(40),
    solucao_validada  BOOLEAN       NOT NULL DEFAULT FALSE,
    validada_por      VARCHAR(60),
    validada_em       TIMESTAMPTZ,
    busca             TSVECTOR,
    versao            INTEGER       NOT NULL DEFAULT 0,
    criado_em         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_em     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_con_registro PRIMARY KEY (id),
    -- sem cascata: usuários são desativados, não apagados
    CONSTRAINT fk_con_registro_autor FOREIGN KEY (autor_id) REFERENCES public.tb_usuario (id),
    -- uma saída confirmada vira um registro só (atendimento_id e saida_id sem chave estrangeira: o
    -- atendimento é apagado pela retenção e o registro continua)
    CONSTRAINT uk_con_registro_saida UNIQUE (saida_id),
    CONSTRAINT ck_con_registro_origem CHECK (origem IN ('gerador', 'importado')),
    CONSTRAINT ck_con_registro_gerado CHECK (origem <> 'gerador' OR saida_id IS NOT NULL),
    CONSTRAINT ck_con_registro_tipo CHECK (tipo IN ('duvida', 'erro', 'alteracao')),
    CONSTRAINT ck_con_registro_titulo CHECK (btrim(titulo) <> ''),
    CONSTRAINT ck_con_registro_situacao CHECK (situacao IN ('resolvido_suporte', 'enviado_desenvolvimento', 'corrigido', 'nao_sera_feito')),
    CONSTRAINT ck_con_registro_validacao CHECK (NOT solucao_validada OR (validada_por IS NOT NULL AND validada_em IS NOT NULL))
);
CREATE INDEX ix_con_registro_busca ON conhecimento.registro USING gin (busca);
CREATE INDEX ix_con_registro_tela_codigo ON conhecimento.registro USING gin (tela_codigo public.gin_trgm_ops);
CREATE INDEX ix_con_registro_mensagem_erro ON conhecimento.registro USING gin (conhecimento.sem_acento(mensagem_erro) public.gin_trgm_ops);
CREATE INDEX ix_con_registro_cliente ON conhecimento.registro USING gin (conhecimento.sem_acento(cliente) public.gin_trgm_ops);
CREATE INDEX ix_con_registro_situacao ON conhecimento.registro (situacao);
CREATE INDEX ix_con_registro_tipo ON conhecimento.registro (tipo);
CREATE INDEX ix_con_registro_autor ON conhecimento.registro (autor_id);
CREATE INDEX ix_con_registro_criado ON conhecimento.registro (criado_em DESC);
COMMENT ON TABLE conhecimento.registro IS 'Um caso da base: identificação, problema, resolução e situação no desenvolvimento. Criado quando o autor confirma o texto gerado.';
COMMENT ON COLUMN conhecimento.registro.busca IS 'Texto de busca (português, sem acento), mantido pelo gatilho tg_con_registro_busca. Pesos: A título, código, tela e erro; B sintoma, causa, módulo e cliente; C resolução.';

CREATE FUNCTION conhecimento.atualizar_busca() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.busca :=
        setweight(to_tsvector('pg_catalog.portuguese'::regconfig, conhecimento.sem_acento(
            concat_ws(' ', NEW.titulo, NEW.tela_codigo, NEW.tela_nome, NEW.mensagem_erro))), 'A') ||
        setweight(to_tsvector('pg_catalog.portuguese'::regconfig, conhecimento.sem_acento(
            concat_ws(' ', NEW.sintoma, NEW.causa, NEW.modulo, NEW.cliente))), 'B') ||
        setweight(to_tsvector('pg_catalog.portuguese'::regconfig, conhecimento.sem_acento(
            coalesce(NEW.resolucao_texto, ''))), 'C');
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_con_registro_busca
    BEFORE INSERT OR UPDATE ON conhecimento.registro
    FOR EACH ROW EXECUTE FUNCTION conhecimento.atualizar_busca();

-- -------------------------------------------------------------- imagens do registro
CREATE TABLE conhecimento.registro_imagem (
    registro_id  UUID          NOT NULL,
    numero       SMALLINT      NOT NULL,
    caminho      VARCHAR(500)  NOT NULL,
    legenda      VARCHAR(300),

    CONSTRAINT pk_con_registro_imagem PRIMARY KEY (registro_id, numero),
    CONSTRAINT fk_con_registro_imagem_registro FOREIGN KEY (registro_id) REFERENCES conhecimento.registro (id) ON DELETE CASCADE,
    CONSTRAINT ck_con_registro_imagem_numero CHECK (numero >= 1)
);
COMMENT ON TABLE conhecimento.registro_imagem IS 'Cópia das imagens escolhidas na saída confirmada (não depende dos arquivos do atendimento).';

-- ------------------------------------------------ histórico da situação no desenvolvimento
CREATE TABLE conhecimento.registro_situacao (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY,
    registro_id      UUID          NOT NULL,
    de               VARCHAR(24),
    para             VARCHAR(24)   NOT NULL,
    chamado_dev      VARCHAR(60),
    versao_correcao  VARCHAR(40),
    por              VARCHAR(60)   NOT NULL,
    em               TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_con_registro_situacao PRIMARY KEY (id),
    CONSTRAINT fk_con_registro_situacao_registro FOREIGN KEY (registro_id) REFERENCES conhecimento.registro (id) ON DELETE CASCADE,
    CONSTRAINT ck_con_registro_situacao_de CHECK (de IS NULL OR de IN ('resolvido_suporte', 'enviado_desenvolvimento', 'corrigido', 'nao_sera_feito')),
    CONSTRAINT ck_con_registro_situacao_para CHECK (para IN ('resolvido_suporte', 'enviado_desenvolvimento', 'corrigido', 'nao_sera_feito'))
);
CREATE INDEX ix_con_registro_situacao_registro ON conhecimento.registro_situacao (registro_id, em);
COMMENT ON TABLE conhecimento.registro_situacao IS 'Quem mudou a situação de um registro (enviado ao desenvolvimento, corrigido na versão X...) e quando.';

-- ------------------------------------------------------- vetores (pesquisa por significado)
CREATE TABLE conhecimento.vetor (
    registro_id   UUID          NOT NULL,
    modelo        VARCHAR(60)   NOT NULL,
    dimensao      SMALLINT      NOT NULL,
    valores       REAL[]        NOT NULL,
    texto_sha256  CHAR(64)      NOT NULL,
    criado_em     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_con_vetor PRIMARY KEY (registro_id, modelo),
    CONSTRAINT fk_con_vetor_registro FOREIGN KEY (registro_id) REFERENCES conhecimento.registro (id) ON DELETE CASCADE,
    CONSTRAINT ck_con_vetor_dimensao CHECK (dimensao BETWEEN 1 AND 8192),
    CONSTRAINT ck_con_vetor_valores CHECK (array_ndims(valores) = 1 AND cardinality(valores) = dimensao),
    CONSTRAINT ck_con_vetor_sha256 CHECK (texto_sha256 ~ '^[0-9a-f]{64}$')
);
COMMENT ON TABLE conhecimento.vetor IS 'Vetor de cada registro para a pesquisa por significado, com o modelo e a dimensão (trocar de modelo = recalcular todos). Registro sem vetor = pendente.';
