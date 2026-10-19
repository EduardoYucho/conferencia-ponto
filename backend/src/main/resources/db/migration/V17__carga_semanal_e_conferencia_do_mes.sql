-- =============================================================================
-- V17 - Carga semanal, conferência do mês e a origem de cada mudança no ponto
--
-- * tb_horario_trabalho.carga_semanal_segundos: o total de horas que a pessoa
--   deve cumprir por semana (vazio = o que o horário soma, como até aqui). A
--   diferença entre a carga e o horário entra no banco de horas, dividida pelos
--   dias úteis; o fim de semana compensa.
-- * tb_ajuste_jornada.origem: o que mudou o dia (ajuste das batidas, dados do RH,
--   lançamento à mão ou exclusão). Os registros que já existiam continuam como
--   AJUSTE (os do RH são reconhecidos pela justificativa, como antes).
-- * tb_conferencia_mes: o mês de uma pessoa conferido por alguém (com os números
--   da hora), e a reabertura. Mês conferido fica travado para mudanças à mão.
-- * Permissão nova ponto.conferir, dada ao perfil Coordenador (o Administrador já
--   tem todas). Nenhum número muda: sem carga informada, nada entra no banco.
-- =============================================================================

ALTER TABLE tb_horario_trabalho
    ADD COLUMN carga_semanal_segundos INTEGER,
    ADD CONSTRAINT ck_horario_trabalho_carga
        CHECK (carga_semanal_segundos IS NULL OR carga_semanal_segundos BETWEEN 3600 AND 288000);

COMMENT ON COLUMN tb_horario_trabalho.carga_semanal_segundos IS
    'Carga semanal que a pessoa deve cumprir, em segundos (vazio = a soma do horário). A diferença para o horário entra no banco de horas.';

ALTER TABLE tb_ajuste_jornada
    ADD COLUMN origem VARCHAR(12) NOT NULL DEFAULT 'AJUSTE',
    ADD CONSTRAINT ck_ajuste_jornada_origem CHECK (origem IN ('AJUSTE', 'RH', 'MANUAL', 'EXCLUSAO'));

COMMENT ON COLUMN tb_ajuste_jornada.origem IS
    'AJUSTE = batidas incluídas ou corrigidas à mão; RH = dados do relatório do RH; MANUAL = lançamento à mão (fim de semana, feriado); EXCLUSAO = dia excluído.';

CREATE TABLE tb_conferencia_mes (
    id                  UUID          NOT NULL,
    usuario_id          UUID          NOT NULL,
    mes                 DATE          NOT NULL,
    conferido_em        TIMESTAMPTZ   NOT NULL,
    conferido_por       VARCHAR(60)   NOT NULL,
    observacao          VARCHAR(300),
    saldo_mes_segundos  INTEGER       NOT NULL,
    trabalhado_segundos INTEGER       NOT NULL,
    dias_registrados    INTEGER       NOT NULL,
    reaberto_em         TIMESTAMPTZ,
    reaberto_por        VARCHAR(60),
    motivo_reabertura   VARCHAR(300),

    CONSTRAINT pk_conferencia_mes PRIMARY KEY (id),
    CONSTRAINT fk_conferencia_mes_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id) ON DELETE CASCADE,
    CONSTRAINT ck_conferencia_mes_primeiro_dia CHECK (EXTRACT(DAY FROM mes) = 1),
    CONSTRAINT ck_conferencia_mes_reabertura CHECK ((reaberto_em IS NULL) = (reaberto_por IS NULL))
);
CREATE UNIQUE INDEX uk_conferencia_mes_vigente ON tb_conferencia_mes (usuario_id, mes) WHERE reaberto_em IS NULL;
CREATE INDEX ix_conferencia_mes_usuario ON tb_conferencia_mes (usuario_id, mes);

COMMENT ON TABLE tb_conferencia_mes IS
    'Conferência do mês de cada pessoa: quem conferiu, quando e os números da hora. Reaberta = deixa de travar (fica no histórico).';
COMMENT ON COLUMN tb_conferencia_mes.mes IS 'Primeiro dia do mês conferido.';

INSERT INTO tb_perfil_permissao (perfil_id, permissao)
SELECT p.id, 'ponto.conferir' FROM tb_perfil_acesso p WHERE p.sistema = 'ROLE_VIEWER'
ON CONFLICT DO NOTHING;

INSERT INTO tb_historico_acesso (quem, perfil_id, descricao)
SELECT 'sistema', p.id, 'Perfil ' || p.nome || ': ganhou "Conferir o mês" (permissão nova)'
  FROM tb_perfil_acesso p WHERE p.sistema = 'ROLE_VIEWER';
