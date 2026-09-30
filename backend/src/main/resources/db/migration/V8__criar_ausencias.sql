-- =============================================================================
-- V8 - Férias, atestados, licenças e folgas (tb_ausencia)
--
-- Dias úteis dentro de uma ausência têm jornada base zero (tipo_dia = 'AUSENCIA'):
-- não geram débito e somem das pendências. Períodos não podem se sobrepor
-- (restrição de exclusão sobre o intervalo de datas; adiável para permitir unir
-- dois períodos vizinhos na mesma transação).
-- =============================================================================

CREATE TABLE tb_ausencia (
    id           UUID          NOT NULL,
    data_inicio  DATE          NOT NULL,
    data_fim     DATE          NOT NULL,
    tipo         VARCHAR(20)   NOT NULL,
    descricao    VARCHAR(200),
    criado_em    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    criado_por   VARCHAR(60),

    CONSTRAINT pk_ausencia PRIMARY KEY (id),
    CONSTRAINT ck_ausencia_tipo CHECK (tipo IN ('FERIAS', 'ATESTADO', 'LICENCA', 'FOLGA')),
    CONSTRAINT ck_ausencia_periodo CHECK (data_fim >= data_inicio AND data_fim - data_inicio < 366),
    CONSTRAINT ex_ausencia_sem_sobreposicao
        EXCLUDE USING gist (daterange(data_inicio, data_fim, '[]') WITH &&) DEFERRABLE INITIALLY DEFERRED
);

COMMENT ON TABLE tb_ausencia IS 'Períodos de férias, atestado, licença ou folga (jornada base zero nos dias úteis).';

ALTER TABLE tb_registro_jornada
    DROP CONSTRAINT ck_registro_jornada_tipo_dia,
    ADD CONSTRAINT ck_registro_jornada_tipo_dia
        CHECK (tipo_dia IN ('UTIL', 'FIM_DE_SEMANA', 'FERIADO', 'AUSENCIA'));

-- Feriados vindos do RH (ex.: Carnaval, Corpus Christi) entram como "EMPRESA".
ALTER TABLE tb_feriado
    DROP CONSTRAINT ck_feriado_abrangencia,
    ADD CONSTRAINT ck_feriado_abrangencia
        CHECK (abrangencia IN ('NACIONAL', 'ESTADUAL', 'MUNICIPAL', 'EMPRESA'));
