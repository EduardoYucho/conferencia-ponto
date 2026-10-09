-- =============================================================================
-- V14 - Módulo de atendimentos (gerador de textos de atendimento com o Gemini)
--
-- * Tudo no schema "atendimento", separado das tabelas do ponto (public). A única ligação
--   com o ponto é tb_usuario. Nenhuma tabela do ponto é alterada.
-- * Para desfazer antes da aprovação, sem tocar no ponto: README, seção "Backup".
-- * Situações e tipos são texto com CHECK (fáceis de ler numa consulta e de estender
--   numa migração nova).
-- =============================================================================

CREATE SCHEMA atendimento;
COMMENT ON SCHEMA atendimento IS 'Módulo de atendimentos: gerador de textos de atendimento com o Gemini.';

-- ----------------------------------------------------------------------- acesso
CREATE TABLE atendimento.acesso (
    usuario_id     UUID          NOT NULL,
    gerador        BOOLEAN       NOT NULL DEFAULT FALSE,
    concedido_por  VARCHAR(60),
    atualizado_em  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_atd_acesso PRIMARY KEY (usuario_id),
    CONSTRAINT fk_atd_acesso_usuario FOREIGN KEY (usuario_id) REFERENCES public.tb_usuario (id) ON DELETE CASCADE
);
COMMENT ON TABLE atendimento.acesso IS 'Quem pode usar o gerador (liberado pelo administrador; independe do perfil do ponto).';

-- ----------------------------------------------------------------- chave do Gemini
CREATE TABLE atendimento.chave_gemini (
    usuario_id             UUID          NOT NULL,
    cifrada                BYTEA         NOT NULL,
    vetor_inicial          BYTEA         NOT NULL,
    versao_chave_mestra    SMALLINT      NOT NULL DEFAULT 1,
    ultimos_caracteres     VARCHAR(4)    NOT NULL,
    nivel_pago_confirmado  BOOLEAN       NOT NULL DEFAULT FALSE,
    situacao               VARCHAR(12)   NOT NULL DEFAULT 'nao_testada',
    testada_em             TIMESTAMPTZ,
    atualizada_em          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_atd_chave_gemini PRIMARY KEY (usuario_id),
    CONSTRAINT fk_atd_chave_gemini_usuario FOREIGN KEY (usuario_id) REFERENCES public.tb_usuario (id) ON DELETE CASCADE,
    CONSTRAINT ck_atd_chave_gemini_vetor CHECK (octet_length(vetor_inicial) = 12),
    CONSTRAINT ck_atd_chave_gemini_versao CHECK (versao_chave_mestra >= 1),
    CONSTRAINT ck_atd_chave_gemini_situacao CHECK (situacao IN ('nao_testada', 'valida', 'recusada', 'sem_cota'))
);
COMMENT ON TABLE atendimento.chave_gemini IS 'Chave da API do Gemini de cada usuário, cifrada (AES-256-GCM; a chave mestra fica em arquivo, fora do banco). Nunca devolvida pela API.';

-- ------------------------------------------------------------------- atendimento
CREATE TABLE atendimento.atendimento (
    id                     UUID          NOT NULL DEFAULT gen_random_uuid(),
    usuario_id             UUID          NOT NULL,
    chamado_digisac        VARCHAR(30)   NOT NULL,
    contato                VARCHAR(150),
    inicio                 TIMESTAMPTZ,
    fim                    TIMESTAMPTZ,
    situacao               VARCHAR(12)   NOT NULL DEFAULT 'novo',
    motivo_pausa           VARCHAR(200),
    conversa               JSONB         NOT NULL,
    linha_do_tempo         JSONB,
    versao_leitor          SMALLINT      NOT NULL,
    links_validos_ate      TIMESTAMPTZ,
    versao_cliente         VARCHAR(40),
    testado_ultima_versao  BOOLEAN,
    caminho_backup         VARCHAR(300),
    chamado_relacionado    VARCHAR(200),
    criado_em              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_em          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    apagar_arquivos_em     TIMESTAMPTZ   NOT NULL,
    apagar_textos_em       TIMESTAMPTZ   NOT NULL,
    arquivos_apagados_em   TIMESTAMPTZ,
    versao                 INTEGER       NOT NULL DEFAULT 0,

    CONSTRAINT pk_atd_atendimento PRIMARY KEY (id),
    -- sem cascata: usuários são desativados, não apagados; apagar um usuário com atendimentos é recusado
    CONSTRAINT fk_atd_atendimento_usuario FOREIGN KEY (usuario_id) REFERENCES public.tb_usuario (id),
    CONSTRAINT ck_atd_atendimento_situacao CHECK (situacao IN ('novo', 'processando', 'pausado', 'pronto', 'com_falhas', 'cancelado')),
    CONSTRAINT ck_atd_atendimento_periodo CHECK (inicio IS NULL OR fim IS NULL OR fim >= inicio),
    CONSTRAINT ck_atd_atendimento_conversa CHECK (jsonb_typeof(conversa) = 'object'),
    CONSTRAINT ck_atd_atendimento_retencao CHECK (apagar_textos_em >= apagar_arquivos_em),
    CONSTRAINT ck_atd_atendimento_versao_leitor CHECK (versao_leitor >= 1)
);
CREATE INDEX ix_atd_atendimento_usuario ON atendimento.atendimento (usuario_id, criado_em DESC);
CREATE INDEX ix_atd_atendimento_chamado ON atendimento.atendimento (usuario_id, chamado_digisac);
CREATE INDEX ix_atd_atendimento_retencao ON atendimento.atendimento (apagar_arquivos_em) WHERE arquivos_apagados_em IS NULL;
COMMENT ON TABLE atendimento.atendimento IS 'Um atendimento: o PDF da conversa do Digisac lido (mensagens já sem chave do bot nem dados de acesso remoto) e os campos informados pelo usuário.';
COMMENT ON COLUMN atendimento.atendimento.conversa IS 'Cabeçalho, mensagens e eventos lidos do PDF, já mascarados (nunca a chave do bot nem dados de acesso remoto).';
COMMENT ON COLUMN atendimento.atendimento.apagar_arquivos_em IS 'Retenção: quando o PDF, os anexos, as ligações, o vídeo e os prints são apagados do disco.';
COMMENT ON COLUMN atendimento.atendimento.apagar_textos_em IS 'Retenção: quando o atendimento inteiro (análises, linha do tempo e saídas) é apagado.';

-- ----------------------------------------------------------------------- arquivo
CREATE TABLE atendimento.arquivo (
    id                UUID          NOT NULL DEFAULT gen_random_uuid(),
    atendimento_id    UUID          NOT NULL,
    origem            VARCHAR(16)   NOT NULL,
    ordem             SMALLINT      NOT NULL,
    nome_original     VARCHAR(255)  NOT NULL,
    categoria         VARCHAR(10)   NOT NULL DEFAULT 'outro',
    tipo_midia        VARCHAR(100),
    tamanho           BIGINT,
    sha256            CHAR(64),
    caminho           VARCHAR(500),
    momento           TIMESTAMPTZ,
    momento_fonte     VARCHAR(12)   NOT NULL DEFAULT 'sem_horario',
    pagina            SMALLINT,
    posicao_y         REAL,
    mensagem_ordem    INTEGER,
    url_download      TEXT,
    url_valida_ate    TIMESTAMPTZ,
    situacao          VARCHAR(14)   NOT NULL DEFAULT 'aguardando',
    erro_codigo       VARCHAR(40),
    erro_mensagem     VARCHAR(500),
    gemini_arquivo    VARCHAR(100),
    gemini_expira_em  TIMESTAMPTZ,
    criado_em         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_atd_arquivo PRIMARY KEY (id),
    CONSTRAINT fk_atd_arquivo_atendimento FOREIGN KEY (atendimento_id) REFERENCES atendimento.atendimento (id) ON DELETE CASCADE,
    CONSTRAINT uk_atd_arquivo_ordem UNIQUE (atendimento_id, origem, ordem),
    CONSTRAINT ck_atd_arquivo_origem CHECK (origem IN ('anexo_conversa', 'ligacao', 'video', 'print_extra')),
    CONSTRAINT ck_atd_arquivo_categoria CHECK (categoria IN ('imagem', 'audio', 'video', 'documento', 'outro')),
    CONSTRAINT ck_atd_arquivo_momento_fonte CHECK (momento_fonte IN ('mensagem', 'arquivo', 'sem_horario')),
    CONSTRAINT ck_atd_arquivo_situacao CHECK (situacao IN ('aguardando', 'baixando', 'pronto', 'falhou', 'vencido', 'nao_suportado', 'removido')),
    CONSTRAINT ck_atd_arquivo_ordem CHECK (ordem >= 1),
    CONSTRAINT ck_atd_arquivo_tamanho CHECK (tamanho IS NULL OR tamanho >= 0),
    CONSTRAINT ck_atd_arquivo_sha256 CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$')
);
COMMENT ON TABLE atendimento.arquivo IS 'Anexos da conversa (baixados pelos links do PDF), ligações, vídeo de reprodução e prints extras de um atendimento.';
COMMENT ON COLUMN atendimento.arquivo.url_download IS 'Link assinado do Digisac (vale 24 h). Apagado depois do download; nunca vai para o log.';

-- ----------------------------------------------------------------------- análise
CREATE TABLE atendimento.analise (
    id              UUID          NOT NULL DEFAULT gen_random_uuid(),
    arquivo_id      UUID          NOT NULL,
    versao_prompt   SMALLINT      NOT NULL,
    modelo          VARCHAR(60)   NOT NULL,
    resultado       JSONB         NOT NULL,
    tokens_entrada  INTEGER,
    tokens_saida    INTEGER,
    duracao_ms      INTEGER,
    criada_em       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_atd_analise PRIMARY KEY (id),
    CONSTRAINT fk_atd_analise_arquivo FOREIGN KEY (arquivo_id) REFERENCES atendimento.arquivo (id) ON DELETE CASCADE,
    CONSTRAINT uk_atd_analise UNIQUE (arquivo_id, versao_prompt, modelo),
    CONSTRAINT ck_atd_analise_tokens CHECK (coalesce(tokens_entrada, 0) >= 0 AND coalesce(tokens_saida, 0) >= 0)
);
COMMENT ON TABLE atendimento.analise IS 'Análise de um arquivo pelo Gemini (transcrição, tela, passos...). Guardada para não ser refeita: outra saída ou "gerar de novo" reaproveita.';

-- ------------------------------------------------------------------------- saída
CREATE TABLE atendimento.saida (
    id              UUID          NOT NULL DEFAULT gen_random_uuid(),
    atendimento_id  UUID          NOT NULL,
    tipo            VARCHAR(14)   NOT NULL,
    versao          SMALLINT      NOT NULL DEFAULT 1,
    situacao        VARCHAR(10)   NOT NULL DEFAULT 'pendente',
    erro_mensagem   VARCHAR(500),
    estrutura       JSONB,
    html            TEXT,
    html_editado    TEXT,
    assunto         VARCHAR(150),
    modelo          VARCHAR(60),
    versao_prompt   SMALLINT,
    tokens_entrada  INTEGER,
    tokens_saida    INTEGER,
    confirmada_em   TIMESTAMPTZ,
    criada_em       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_atd_saida PRIMARY KEY (id),
    CONSTRAINT fk_atd_saida_atendimento FOREIGN KEY (atendimento_id) REFERENCES atendimento.atendimento (id) ON DELETE CASCADE,
    CONSTRAINT uk_atd_saida_versao UNIQUE (atendimento_id, tipo, versao),
    CONSTRAINT ck_atd_saida_tipo CHECK (tipo IN ('resumo', 'dev_erro', 'dev_alteracao')),
    CONSTRAINT ck_atd_saida_situacao CHECK (situacao IN ('pendente', 'redigindo', 'pronta', 'falhou', 'confirmada')),
    CONSTRAINT ck_atd_saida_confirmada CHECK ((situacao = 'confirmada') = (confirmada_em IS NOT NULL)),
    CONSTRAINT ck_atd_saida_versao CHECK (versao >= 1),
    CONSTRAINT ck_atd_saida_tokens CHECK (coalesce(tokens_entrada, 0) >= 0 AND coalesce(tokens_saida, 0) >= 0)
);
COMMENT ON TABLE atendimento.saida IS 'Texto gerado (resumo, chamado de erro ou de alteração): a estrutura devolvida pelo Gemini e o HTML montado pelo servidor.';

-- ------------------------------------------------------------------------ imagem
CREATE TABLE atendimento.imagem (
    id                 UUID          NOT NULL DEFAULT gen_random_uuid(),
    saida_id           UUID          NOT NULL,
    numero             SMALLINT      NOT NULL,
    arquivo_id         UUID,
    instante_video_ms  INTEGER,
    caminho            VARCHAR(500)  NOT NULL,
    largura            INTEGER,
    altura             INTEGER,
    legenda            VARCHAR(300),
    marcacoes          JSONB,
    com_marcacao       BOOLEAN       NOT NULL DEFAULT FALSE,
    incluida           BOOLEAN       NOT NULL DEFAULT TRUE,

    CONSTRAINT pk_atd_imagem PRIMARY KEY (id),
    CONSTRAINT fk_atd_imagem_saida FOREIGN KEY (saida_id) REFERENCES atendimento.saida (id) ON DELETE CASCADE,
    CONSTRAINT fk_atd_imagem_arquivo FOREIGN KEY (arquivo_id) REFERENCES atendimento.arquivo (id) ON DELETE SET NULL,
    -- adiável: renumerar as imagens (tirar ou reordenar) troca os números numa transação só
    CONSTRAINT uk_atd_imagem_numero UNIQUE (saida_id, numero) DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT ck_atd_imagem_numero CHECK (numero >= 1),
    CONSTRAINT ck_atd_imagem_instante CHECK (instante_video_ms IS NULL OR instante_video_ms >= 0),
    CONSTRAINT ck_atd_imagem_tamanho CHECK ((largura IS NULL OR largura > 0) AND (altura IS NULL OR altura > 0))
);
CREATE INDEX ix_atd_imagem_arquivo ON atendimento.imagem (arquivo_id) WHERE arquivo_id IS NOT NULL;
COMMENT ON TABLE atendimento.imagem IS 'Imagens numeradas de uma saída ("Imagem N" no texto): anexo, print ou quadro do vídeo. Nunca criadas pelo modelo.';

-- ------------------------------------------------------------------------ tarefa
CREATE TABLE atendimento.tarefa (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY,
    atendimento_id   UUID          NOT NULL,
    arquivo_id       UUID,
    saida_id         UUID,
    tipo             VARCHAR(16)   NOT NULL,
    situacao         VARCHAR(10)   NOT NULL DEFAULT 'pendente',
    tentativas       SMALLINT      NOT NULL DEFAULT 0,
    max_tentativas   SMALLINT      NOT NULL DEFAULT 5,
    executar_apos    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    em_execucao_ate  TIMESTAMPTZ,
    erro_codigo      VARCHAR(40),
    erro_mensagem    VARCHAR(500),
    criada_em        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizada_em    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_atd_tarefa PRIMARY KEY (id),
    CONSTRAINT fk_atd_tarefa_atendimento FOREIGN KEY (atendimento_id) REFERENCES atendimento.atendimento (id) ON DELETE CASCADE,
    CONSTRAINT fk_atd_tarefa_arquivo FOREIGN KEY (arquivo_id) REFERENCES atendimento.arquivo (id) ON DELETE CASCADE,
    CONSTRAINT fk_atd_tarefa_saida FOREIGN KEY (saida_id) REFERENCES atendimento.saida (id) ON DELETE CASCADE,
    CONSTRAINT ck_atd_tarefa_tipo CHECK (tipo IN ('baixar', 'analisar', 'linha_do_tempo', 'redigir', 'preparar_imagens')),
    CONSTRAINT ck_atd_tarefa_situacao CHECK (situacao IN ('pendente', 'executando', 'concluida', 'falhou', 'cancelada', 'pausada')),
    CONSTRAINT ck_atd_tarefa_tentativas CHECK (tentativas >= 0 AND max_tentativas >= 1),
    -- quem executa marca até quando a tarefa é dela; prazo vencido = volta para a fila (serviço reiniciado)
    CONSTRAINT ck_atd_tarefa_execucao CHECK ((situacao = 'executando') = (em_execucao_ate IS NOT NULL))
);
CREATE INDEX ix_atd_tarefa_fila ON atendimento.tarefa (executar_apos) WHERE situacao = 'pendente';
CREATE INDEX ix_atd_tarefa_executando ON atendimento.tarefa (em_execucao_ate) WHERE situacao = 'executando';
CREATE INDEX ix_atd_tarefa_atendimento ON atendimento.tarefa (atendimento_id);
CREATE INDEX ix_atd_tarefa_arquivo ON atendimento.tarefa (arquivo_id) WHERE arquivo_id IS NOT NULL;
CREATE INDEX ix_atd_tarefa_saida ON atendimento.tarefa (saida_id) WHERE saida_id IS NOT NULL;
COMMENT ON TABLE atendimento.tarefa IS 'Fila do processamento em segundo plano (uma tarefa por anexo e por etapa), lida com FOR UPDATE SKIP LOCKED.';
