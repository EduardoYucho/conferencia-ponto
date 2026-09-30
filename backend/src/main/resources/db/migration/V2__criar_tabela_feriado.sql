-- =============================================================================
-- V2 - Calendário de feriados (classificação de dias não úteis)
--
-- Sábados e domingos são classificados automaticamente pelo domínio.
-- Feriados precisam estar cadastrados aqui. A carga inicial contém apenas os
-- feriados NACIONAIS de 2026; inclua os estaduais/municipais da sua cidade.
-- =============================================================================

CREATE TABLE tb_feriado (
    data         DATE          NOT NULL,
    descricao    VARCHAR(120)  NOT NULL,
    abrangencia  VARCHAR(20)   NOT NULL DEFAULT 'NACIONAL',

    CONSTRAINT pk_feriado PRIMARY KEY (data),
    CONSTRAINT ck_feriado_abrangencia CHECK (abrangencia IN ('NACIONAL', 'ESTADUAL', 'MUNICIPAL'))
);

INSERT INTO tb_feriado (data, descricao, abrangencia) VALUES
    ('2026-01-01', 'Confraternização Universal',            'NACIONAL'),
    ('2026-04-03', 'Paixão de Cristo',                      'NACIONAL'),
    ('2026-04-21', 'Tiradentes',                            'NACIONAL'),
    ('2026-05-01', 'Dia do Trabalho',                       'NACIONAL'),
    ('2026-09-07', 'Independência do Brasil',               'NACIONAL'),
    ('2026-10-12', 'Nossa Senhora Aparecida',               'NACIONAL'),
    ('2026-11-02', 'Finados',                               'NACIONAL'),
    ('2026-11-15', 'Proclamação da República',              'NACIONAL'),
    ('2026-11-20', 'Dia Nacional de Zumbi e da Consciência Negra', 'NACIONAL'),
    ('2026-12-25', 'Natal',                                 'NACIONAL');
