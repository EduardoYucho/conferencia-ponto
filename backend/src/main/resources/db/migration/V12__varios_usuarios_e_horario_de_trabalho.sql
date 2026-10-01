-- =============================================================================
-- V12 - Vários usuários, cada um com os próprios dados, horário e pasta de PDFs
--
-- * Todas as tabelas de dados ganham usuario_id (o "titular" dos dados). O que já
--   existe passa a ser do primeiro usuário ADMIN (ou USER) cadastrado.
-- * tb_horario_trabalho: horário por dia da semana, com vigência ("a partir de"):
--   mudar o horário não recalcula o passado com o horário novo.
-- * tb_usuario: pasta dos comprovantes e troca de senha obrigatória no 1º acesso.
-- * Feriados continuam valendo para todos.
-- =============================================================================

ALTER TABLE tb_usuario
    ADD COLUMN pasta_comprovantes VARCHAR(500),
    ADD COLUMN trocar_senha       BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN tb_usuario.pasta_comprovantes IS 'Pasta monitorada com os PDFs de comprovante deste usuário (local ou de rede).';

-- ----------------------------------------------------------------------- horário
-- Cada dia da semana: períodos "HH:MM-HH:MM" separados por espaço (até 3); vazio = sem expediente.
CREATE TABLE tb_horario_trabalho (
    id                  UUID          NOT NULL,
    usuario_id          UUID          NOT NULL,
    vigente_desde       DATE          NOT NULL,
    tolerancia_minutos  SMALLINT      NOT NULL DEFAULT 5,
    seg                 VARCHAR(40),
    ter                 VARCHAR(40),
    qua                 VARCHAR(40),
    qui                 VARCHAR(40),
    sex                 VARCHAR(40),
    sab                 VARCHAR(40),
    dom                 VARCHAR(40),
    criado_em           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    criado_por          VARCHAR(60),

    CONSTRAINT pk_horario_trabalho PRIMARY KEY (id),
    CONSTRAINT fk_horario_trabalho_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id) ON DELETE CASCADE,
    CONSTRAINT uk_horario_trabalho_vigencia UNIQUE (usuario_id, vigente_desde),
    CONSTRAINT ck_horario_trabalho_tolerancia CHECK (tolerancia_minutos BETWEEN 0 AND 60)
);

COMMENT ON TABLE tb_horario_trabalho IS 'Horário de trabalho por usuário e dia da semana, com vigência.';

-- ----------------------------------------------------------------------- titular
DO $$
DECLARE
    titular UUID;
    com_dados BOOLEAN;
BEGIN
    SELECT u.id INTO titular
      FROM tb_usuario u
      JOIN tb_usuario_role ur ON ur.usuario_id = u.id
      JOIN tb_role r          ON r.id = ur.role_id
     WHERE r.nome IN ('ROLE_ADMIN', 'ROLE_USER')
     ORDER BY (r.nome = 'ROLE_ADMIN') DESC, u.criado_em
     LIMIT 1;

    SELECT EXISTS (SELECT 1 FROM tb_registro_jornada) OR EXISTS (SELECT 1 FROM tb_comprovante_ponto)
        OR EXISTS (SELECT 1 FROM tb_ausencia) OR EXISTS (SELECT 1 FROM tb_ciclo_banco)
        OR EXISTS (SELECT 1 FROM tb_lancamento_banco) OR EXISTS (SELECT 1 FROM tb_relatorio_rh)
        OR EXISTS (SELECT 1 FROM tb_notificacao) OR EXISTS (SELECT 1 FROM tb_ajuste_jornada)
      INTO com_dados;

    IF titular IS NULL AND com_dados THEN
        RAISE EXCEPTION 'Há dados de ponto, mas nenhum usuário ADMIN/USER para ser o dono deles.';
    END IF;

    ALTER TABLE tb_registro_jornada  ADD COLUMN usuario_id UUID;
    ALTER TABLE tb_comprovante_ponto ADD COLUMN usuario_id UUID;
    ALTER TABLE tb_ajuste_jornada    ADD COLUMN usuario_id UUID;
    ALTER TABLE tb_ausencia          ADD COLUMN usuario_id UUID;
    ALTER TABLE tb_ciclo_banco       ADD COLUMN usuario_id UUID;
    ALTER TABLE tb_notificacao       ADD COLUMN usuario_id UUID;
    ALTER TABLE tb_relatorio_rh      ADD COLUMN usuario_id UUID;
    ALTER TABLE tb_divergencia       ADD COLUMN usuario_id UUID;
    ALTER TABLE tb_lancamento_banco  ADD COLUMN usuario_id UUID;

    UPDATE tb_registro_jornada  SET usuario_id = titular;
    UPDATE tb_comprovante_ponto SET usuario_id = titular;
    UPDATE tb_ajuste_jornada    SET usuario_id = titular;
    UPDATE tb_ausencia          SET usuario_id = titular;
    UPDATE tb_ciclo_banco       SET usuario_id = titular;
    UPDATE tb_notificacao       SET usuario_id = titular;
    UPDATE tb_relatorio_rh      SET usuario_id = titular;
    UPDATE tb_divergencia       SET usuario_id = titular;
    UPDATE tb_lancamento_banco  SET usuario_id = titular;
END $$;

-- NOT NULL + chave estrangeira em todas
ALTER TABLE tb_registro_jornada
    ALTER COLUMN usuario_id SET NOT NULL,
    ADD CONSTRAINT fk_registro_jornada_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id),
    DROP CONSTRAINT uk_registro_jornada_data,
    ADD CONSTRAINT uk_registro_jornada_usuario_data UNIQUE (usuario_id, data_referencia);

ALTER TABLE tb_comprovante_ponto
    ALTER COLUMN usuario_id SET NOT NULL,
    ADD CONSTRAINT fk_comprovante_ponto_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id);
DROP INDEX ux_comprovante_ponto_batida_importada;
CREATE UNIQUE INDEX ux_comprovante_ponto_batida_importada
    ON tb_comprovante_ponto (usuario_id, data_hora_batida)
    WHERE status = 'IMPORTADO';
CREATE INDEX ix_comprovante_ponto_usuario ON tb_comprovante_ponto (usuario_id, processado_em DESC);

ALTER TABLE tb_ajuste_jornada
    ALTER COLUMN usuario_id SET NOT NULL,
    ADD CONSTRAINT fk_ajuste_jornada_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id);
DROP INDEX ix_ajuste_jornada_data;
CREATE INDEX ix_ajuste_jornada_data ON tb_ajuste_jornada (usuario_id, data_referencia, ajustado_em DESC);

-- Sobreposição de ausências passa a ser conferida pela aplicação (por usuário)
ALTER TABLE tb_ausencia
    ALTER COLUMN usuario_id SET NOT NULL,
    ADD CONSTRAINT fk_ausencia_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id),
    DROP CONSTRAINT ex_ausencia_sem_sobreposicao;
CREATE INDEX ix_ausencia_usuario ON tb_ausencia (usuario_id, data_inicio);

-- Um ciclo aberto por usuário; sobreposição conferida pela aplicação
ALTER TABLE tb_ciclo_banco
    ALTER COLUMN usuario_id SET NOT NULL,
    ADD CONSTRAINT fk_ciclo_banco_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id),
    DROP CONSTRAINT ex_ciclo_banco_sem_sobreposicao;
DROP INDEX ux_ciclo_banco_um_aberto;
CREATE UNIQUE INDEX ux_ciclo_banco_um_aberto ON tb_ciclo_banco (usuario_id) WHERE status = 'ABERTO';

ALTER TABLE tb_notificacao
    ALTER COLUMN usuario_id SET NOT NULL,
    ADD CONSTRAINT fk_notificacao_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id) ON DELETE CASCADE,
    DROP CONSTRAINT uk_notificacao_chave,
    ADD CONSTRAINT uk_notificacao_chave UNIQUE (usuario_id, chave);
DROP INDEX ix_notificacao_criada_em;
CREATE INDEX ix_notificacao_criada_em ON tb_notificacao (usuario_id, criada_em DESC);

ALTER TABLE tb_relatorio_rh
    ALTER COLUMN usuario_id SET NOT NULL,
    ADD CONSTRAINT fk_relatorio_rh_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id),
    DROP CONSTRAINT uk_relatorio_rh_hash,
    ADD CONSTRAINT uk_relatorio_rh_hash UNIQUE (usuario_id, hash_sha256);

ALTER TABLE tb_divergencia
    ALTER COLUMN usuario_id SET NOT NULL,
    ADD CONSTRAINT fk_divergencia_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id),
    DROP CONSTRAINT uk_divergencia_data,
    ADD CONSTRAINT uk_divergencia_data UNIQUE (usuario_id, data);
DROP INDEX ix_divergencia_status;
CREATE INDEX ix_divergencia_status ON tb_divergencia (usuario_id, status, data);

ALTER TABLE tb_lancamento_banco
    ALTER COLUMN usuario_id SET NOT NULL,
    ADD CONSTRAINT fk_lancamento_banco_usuario FOREIGN KEY (usuario_id) REFERENCES tb_usuario (id);
DROP INDEX ix_lancamento_banco_data;
CREATE INDEX ix_lancamento_banco_data ON tb_lancamento_banco (usuario_id, data);

-- ----------------------------------------------------------------------- saldo mensal por usuário
DROP VIEW vw_saldo_mensal;
CREATE VIEW vw_saldo_mensal AS
WITH mensal AS (
    SELECT
        r.usuario_id,
        EXTRACT(YEAR  FROM r.data_referencia)::INT                                              AS ano,
        EXTRACT(MONTH FROM r.data_referencia)::INT                                              AS mes,
        COUNT(*)::INT                                                                           AS dias_registrados,
        COUNT(*) FILTER (WHERE r.saldo_diario_segundos IS NULL)::INT                            AS dias_em_aberto,
        COALESCE(SUM(r.segundos_trabalhados)      FILTER (WHERE r.saldo_diario_segundos IS NOT NULL), 0)::INT AS segundos_trabalhados,
        COALESCE(SUM(r.jornada_prevista_segundos) FILTER (WHERE r.saldo_diario_segundos IS NOT NULL), 0)::INT AS segundos_previstos,
        COALESCE(SUM(r.saldo_diario_segundos), 0)::INT                                          AS saldo_mensal_segundos
    FROM tb_registro_jornada r
    GROUP BY 1, 2, 3
)
SELECT
    m.usuario_id,
    m.ano,
    m.mes,
    m.dias_registrados,
    m.dias_em_aberto,
    m.segundos_trabalhados,
    m.segundos_previstos,
    m.saldo_mensal_segundos,
    SUM(m.saldo_mensal_segundos) OVER (PARTITION BY m.usuario_id, m.ano ORDER BY m.mes)::INT AS saldo_anual_acumulado_segundos
FROM mensal m;

COMMENT ON VIEW vw_saldo_mensal IS 'Saldo mensal e anual acumulado das jornadas, por usuário, em segundos.';
