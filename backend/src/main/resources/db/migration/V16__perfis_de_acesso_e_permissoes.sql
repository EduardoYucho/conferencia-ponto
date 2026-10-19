-- =============================================================================
-- V16 - Controle de acessos: perfis editáveis, vários perfis por pessoa e ajustes
--
-- * Permissão: o que dá para liberar, por tela e ação (ex.: ponto.lancar). A lista
--   fica no código (o ponto e cada módulo declaram as suas); aqui só o código.
-- * Perfil: um grupo de permissões, criado e editado na tela. O "Administrador"
--   tem todas (inclusive as que vierem depois) e não muda.
-- * Cada pessoa tem um ou mais perfis e pode ter ajustes só dela: uma permissão
--   liberada a mais ou retirada.
-- * Ninguém ganha nem perde acesso: os perfis antigos (tb_usuario_role) viram os
--   perfis novos equivalentes, e as liberações dos módulos viram ajustes (menos
--   para o administrador, que já tem tudo pelo perfil).
-- * tb_role, tb_usuario_role, atendimento.acesso e conhecimento.acesso ficam como
--   estão (sem uso), para dar para voltar atrás.
-- =============================================================================

CREATE TABLE tb_perfil_acesso (
    id              UUID          NOT NULL DEFAULT gen_random_uuid(),
    nome            VARCHAR(60)   NOT NULL,
    descricao       VARCHAR(200),
    todas           BOOLEAN       NOT NULL DEFAULT FALSE,
    sistema         VARCHAR(30),
    criado_em       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_em   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_por  VARCHAR(60),

    CONSTRAINT pk_perfil_acesso PRIMARY KEY (id),
    CONSTRAINT uk_perfil_acesso_sistema UNIQUE (sistema),
    CONSTRAINT ck_perfil_acesso_nome CHECK (length(btrim(nome)) >= 2),
    CONSTRAINT ck_perfil_acesso_todas CHECK (NOT todas OR sistema = 'ROLE_ADMIN')
);
CREATE UNIQUE INDEX uk_perfil_acesso_nome ON tb_perfil_acesso (lower(nome));

COMMENT ON TABLE tb_perfil_acesso IS 'Perfis de acesso: grupos de permissões, criados e editados na tela Perfis de acesso.';
COMMENT ON COLUMN tb_perfil_acesso.todas IS 'Tem todas as permissões, inclusive as que vierem depois (só o Administrador).';
COMMENT ON COLUMN tb_perfil_acesso.sistema IS 'Perfil que o sistema cria (ROLE_ADMIN, ROLE_USER, ROLE_VIEWER): não é excluído; vazio = criado na tela.';

CREATE TABLE tb_perfil_permissao (
    perfil_id  UUID         NOT NULL,
    permissao  VARCHAR(60)  NOT NULL,

    CONSTRAINT pk_perfil_permissao PRIMARY KEY (perfil_id, permissao),
    CONSTRAINT fk_perfil_permissao_perfil FOREIGN KEY (perfil_id) REFERENCES tb_perfil_acesso (id) ON DELETE CASCADE,
    CONSTRAINT ck_perfil_permissao_codigo CHECK (permissao ~ '^[a-z]+(\.[a-z]+)*$')
);

COMMENT ON TABLE tb_perfil_permissao IS 'O que cada perfil libera (código da permissão, ex.: ponto.lancar).';

CREATE TABLE tb_usuario_perfil (
    usuario_id     UUID         NOT NULL,
    perfil_id      UUID         NOT NULL,
    atribuido_em   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atribuido_por  VARCHAR(60),

    CONSTRAINT pk_usuario_perfil PRIMARY KEY (usuario_id, perfil_id),
    CONSTRAINT fk_usuario_perfil_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id) ON DELETE CASCADE,
    CONSTRAINT fk_usuario_perfil_perfil  FOREIGN KEY (perfil_id)  REFERENCES tb_perfil_acesso (id) ON DELETE CASCADE
);
CREATE INDEX ix_usuario_perfil_perfil ON tb_usuario_perfil (perfil_id);

COMMENT ON TABLE tb_usuario_perfil IS 'Os perfis de cada pessoa (um ou mais): ela pode a soma do que eles liberam.';

CREATE TABLE tb_usuario_permissao (
    usuario_id    UUID         NOT NULL,
    permissao     VARCHAR(60)  NOT NULL,
    liberada      BOOLEAN      NOT NULL,
    alterado_em   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    alterado_por  VARCHAR(60),

    CONSTRAINT pk_usuario_permissao PRIMARY KEY (usuario_id, permissao),
    CONSTRAINT fk_usuario_permissao_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id) ON DELETE CASCADE,
    CONSTRAINT ck_usuario_permissao_codigo CHECK (permissao ~ '^[a-z]+(\.[a-z]+)*$')
);

COMMENT ON TABLE tb_usuario_permissao IS 'Ajustes só daquela pessoa: liberada = a mais do que os perfis dão; não liberada = retirada mesmo que um perfil dê.';

CREATE TABLE tb_historico_acesso (
    id          BIGSERIAL      NOT NULL,
    quando      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    quem        VARCHAR(60)    NOT NULL,
    usuario_id  UUID,
    perfil_id   UUID,
    descricao   VARCHAR(1000)  NOT NULL,

    CONSTRAINT pk_historico_acesso PRIMARY KEY (id),
    CONSTRAINT fk_historico_acesso_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id) ON DELETE SET NULL
);
CREATE INDEX ix_historico_acesso_quando ON tb_historico_acesso (quando DESC);
CREATE INDEX ix_historico_acesso_usuario ON tb_historico_acesso (usuario_id, quando DESC);

COMMENT ON TABLE tb_historico_acesso IS 'Quem mudou o acesso de quem (perfis, ajustes, perfis editados), e quando. perfil_id fica mesmo depois de o perfil ser excluído.';

-- ------------------------------------------------------------- perfis do sistema
INSERT INTO tb_perfil_acesso (nome, descricao, todas, sistema, atualizado_por) VALUES
    ('Administrador', 'Tudo liberado, inclusive o que vier nas próximas versões.', TRUE, 'ROLE_ADMIN', 'sistema'),
    ('Usuário', 'Registra e confere o próprio ponto.', FALSE, 'ROLE_USER', 'sistema'),
    ('Coordenador', 'Consulta o ponto de todos, sem alterar.', FALSE, 'ROLE_VIEWER', 'sistema');

INSERT INTO tb_perfil_permissao (perfil_id, permissao)
SELECT p.id, x.permissao
  FROM tb_perfil_acesso p
  JOIN (VALUES ('ROLE_USER', 'ponto.proprio'),
               ('ROLE_USER', 'ponto.lancar'),
               ('ROLE_VIEWER', 'ponto.equipe')) AS x (sistema, permissao) ON x.sistema = p.sistema;

-- --------------------------------------------- quem tinha cada perfil continua com ele
INSERT INTO tb_usuario_perfil (usuario_id, perfil_id, atribuido_por)
SELECT ur.usuario_id, p.id, 'sistema'
  FROM tb_usuario_role ur
  JOIN tb_role r          ON r.id = ur.role_id
  JOIN tb_perfil_acesso p ON p.sistema = r.nome;

-- ------------------- liberações dos módulos viram ajustes (o administrador já tem tudo)
DO $$
BEGIN
    IF to_regclass('atendimento.acesso') IS NOT NULL THEN
        INSERT INTO tb_usuario_permissao (usuario_id, permissao, liberada, alterado_em, alterado_por)
        SELECT a.usuario_id, 'atendimentos.usar', TRUE, a.atualizado_em, a.concedido_por
          FROM atendimento.acesso a
         WHERE a.gerador
           AND NOT EXISTS (SELECT 1 FROM tb_usuario_role ur JOIN tb_role r ON r.id = ur.role_id
                            WHERE ur.usuario_id = a.usuario_id AND r.nome = 'ROLE_ADMIN');
        COMMENT ON TABLE atendimento.acesso IS 'Sem uso desde a V16 (o acesso aos atendimentos é a permissão atendimentos.usar).';
    END IF;
    IF to_regclass('conhecimento.acesso') IS NOT NULL THEN
        INSERT INTO tb_usuario_permissao (usuario_id, permissao, liberada, alterado_em, alterado_por)
        SELECT b.usuario_id, x.permissao, TRUE, b.atualizado_em, b.concedido_por
          FROM conhecimento.acesso b
          JOIN LATERAL (VALUES ('base.pesquisar', b.pesquisar OR b.curar), ('base.curar', b.curar)) AS x (permissao, tem)
               ON x.tem
         WHERE NOT EXISTS (SELECT 1 FROM tb_usuario_role ur JOIN tb_role r ON r.id = ur.role_id
                            WHERE ur.usuario_id = b.usuario_id AND r.nome = 'ROLE_ADMIN');
        COMMENT ON TABLE conhecimento.acesso IS 'Sem uso desde a V16 (o acesso à base são as permissões base.pesquisar e base.curar).';
    END IF;
END
$$;

INSERT INTO tb_historico_acesso (quem, descricao)
VALUES ('sistema', 'Perfis de acesso criados (Administrador, Usuário e Coordenador); cada pessoa ficou com o acesso que já tinha.');

COMMENT ON TABLE tb_role IS 'Sem uso desde a V16 (perfis em tb_perfil_acesso).';
COMMENT ON TABLE tb_usuario_role IS 'Sem uso desde a V16 (perfis de cada pessoa em tb_usuario_perfil).';
