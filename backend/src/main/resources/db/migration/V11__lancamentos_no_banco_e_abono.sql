-- =============================================================================
-- V11 - Lançamentos avulsos no banco de horas e ausência "abono"
--
-- tb_lancamento_banco: débito (segundos < 0) ou crédito (segundos > 0) no banco
-- de horas numa data, com justificativa — ex.: compensar horas a mais com uma
-- folga, horas pagas pela empresa, correção do RH. Não muda a jornada do dia:
-- entra no saldo do mês e do ciclo, à parte das batidas (a conciliação com o RH
-- continua comparando só as jornadas).
--
-- ABONO: falta justificada que não é férias, atestado, licença nem folga
-- (jornada base zero, como as demais ausências; a justificativa é obrigatória).
-- =============================================================================

CREATE TABLE tb_lancamento_banco (
    id          UUID          NOT NULL,
    data        DATE          NOT NULL,
    segundos    INTEGER       NOT NULL,
    descricao   VARCHAR(200)  NOT NULL,
    criado_em   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    criado_por  VARCHAR(60),

    CONSTRAINT pk_lancamento_banco PRIMARY KEY (id),
    -- até 300 horas por lançamento, para mais ou para menos
    CONSTRAINT ck_lancamento_banco_segundos CHECK (segundos <> 0 AND segundos BETWEEN -1080000 AND 1080000),
    CONSTRAINT ck_lancamento_banco_descricao CHECK (length(trim(descricao)) > 0)
);

CREATE INDEX ix_lancamento_banco_data ON tb_lancamento_banco (data);

COMMENT ON TABLE tb_lancamento_banco IS 'Débitos/créditos avulsos no banco de horas (compensação, horas pagas, correções).';

ALTER TABLE tb_ausencia
    DROP CONSTRAINT ck_ausencia_tipo,
    ADD CONSTRAINT ck_ausencia_tipo CHECK (tipo IN ('FERIAS', 'ATESTADO', 'LICENCA', 'FOLGA', 'ABONO'));

-- Feriados nacionais de 2027 (os estaduais/municipais e pontos facultativos se cadastram pela tela)
INSERT INTO tb_feriado (data, descricao, abrangencia) VALUES
    ('2027-01-01', 'Confraternização Universal',                   'NACIONAL'),
    ('2027-03-26', 'Paixão de Cristo',                             'NACIONAL'),
    ('2027-04-21', 'Tiradentes',                                   'NACIONAL'),
    ('2027-05-01', 'Dia do Trabalho',                              'NACIONAL'),
    ('2027-09-07', 'Independência do Brasil',                      'NACIONAL'),
    ('2027-10-12', 'Nossa Senhora Aparecida',                      'NACIONAL'),
    ('2027-11-02', 'Finados',                                      'NACIONAL'),
    ('2027-11-15', 'Proclamação da República',                     'NACIONAL'),
    ('2027-11-20', 'Dia Nacional de Zumbi e da Consciência Negra', 'NACIONAL'),
    ('2027-12-25', 'Natal',                                        'NACIONAL')
ON CONFLICT (data) DO NOTHING;
