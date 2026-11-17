-- =============================================================================
-- V19 - Desfazer (ou mover) a importação de um comprovante que entrou no ponto
--       da pessoa errada
--
-- * tb_comprovante_ponto: o mesmo PDF passa a poder ter um registro por pessoa
--   (o hash era único no sistema todo; agora é único por pessoa). Assim o PDF
--   tirado do ponto de alguém fica registrado como DESFEITO para essa pessoa (a
--   pasta dela não o importa de novo) e pode entrar no ponto da pessoa certa.
--   Nenhum registro existente muda.
-- * tb_ajuste_jornada.origem: DESFEITO = a batida saiu porque a importação do
--   comprovante dela foi desfeita.
-- =============================================================================

ALTER TABLE tb_comprovante_ponto
    DROP CONSTRAINT uk_comprovante_ponto_hash,
    ADD CONSTRAINT uk_comprovante_ponto_usuario_hash UNIQUE (usuario_id, hash_sha256),
    DROP CONSTRAINT ck_comprovante_ponto_status,
    ADD CONSTRAINT ck_comprovante_ponto_status
        CHECK (status IN ('IMPORTADO', 'DUPLICADO', 'REJEITADO', 'INVALIDO', 'DESFEITO'));

-- "este PDF está no ponto de outra pessoa?" (antes, o índice da chave única)
CREATE INDEX ix_comprovante_ponto_hash ON tb_comprovante_ponto (hash_sha256);

COMMENT ON COLUMN tb_comprovante_ponto.status IS
    'IMPORTADO, DUPLICADO, REJEITADO, INVALIDO ou DESFEITO (a importação foi desfeita: o PDF era de outra pessoa).';

ALTER TABLE tb_ajuste_jornada
    DROP CONSTRAINT ck_ajuste_jornada_origem,
    ADD CONSTRAINT ck_ajuste_jornada_origem
        CHECK (origem IN ('AJUSTE', 'RH', 'MANUAL', 'EXCLUSAO', 'DESFEITO'));

COMMENT ON COLUMN tb_ajuste_jornada.origem IS
    'AJUSTE = batidas incluídas ou corrigidas à mão; RH = dados do relatório do RH; MANUAL = lançamento à mão (fim de semana, feriado); EXCLUSAO = dia excluído; DESFEITO = importação de comprovante desfeita.';
