package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveCifrada;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** atendimento.chave_gemini: uma linha por usuário, com a chave cifrada (nunca o texto da chave). */
@Repository
class ChavesGeminiJdbc implements ChavesGemini {

    private final JdbcClient jdbc;

    ChavesGeminiJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ChaveGemini> buscar(UUID usuarioId) {
        return jdbc.sql("SELECT * FROM atendimento.chave_gemini WHERE usuario_id = :usuario")
                .param("usuario", usuarioId)
                .query(ChavesGeminiJdbc::chave).optional();
    }

    @Override
    public void salvar(ChaveGemini chave) {
        jdbc.sql("""
                        INSERT INTO atendimento.chave_gemini (usuario_id, cifrada, vetor_inicial, versao_chave_mestra,
                               ultimos_caracteres, nivel_pago_confirmado, situacao, testada_em, atualizada_em)
                        VALUES (:usuario, :cifrada, :vetor, :versao, :ultimos, :nivelPago, :situacao, :testada, :atualizada)
                        ON CONFLICT (usuario_id) DO UPDATE SET
                            cifrada = EXCLUDED.cifrada, vetor_inicial = EXCLUDED.vetor_inicial,
                            versao_chave_mestra = EXCLUDED.versao_chave_mestra,
                            ultimos_caracteres = EXCLUDED.ultimos_caracteres,
                            nivel_pago_confirmado = EXCLUDED.nivel_pago_confirmado, situacao = EXCLUDED.situacao,
                            testada_em = EXCLUDED.testada_em, atualizada_em = EXCLUDED.atualizada_em
                        """)
                .param("usuario", chave.usuarioId())
                .param("cifrada", chave.cifrada().cifrada())
                .param("vetor", chave.cifrada().vetorInicial())
                .param("versao", (short) chave.cifrada().versaoChaveMestra())
                .param("ultimos", chave.ultimosCaracteres())
                .param("nivelPago", chave.nivelPagoConfirmado())
                .param("situacao", chave.situacao().codigo())
                .param("testada", utc(chave.testadaEm()))
                .param("atualizada", utc(chave.atualizadaEm() == null ? Instant.now() : chave.atualizadaEm()))
                .update();
    }

    @Override
    public void atualizarSituacao(UUID usuarioId, SituacaoDaChave situacao, Instant testadaEm) {
        jdbc.sql("UPDATE atendimento.chave_gemini SET situacao = :situacao, testada_em = :testada WHERE usuario_id = :usuario")
                .param("situacao", situacao.codigo())
                .param("testada", utc(testadaEm))
                .param("usuario", usuarioId)
                .update();
    }

    @Override
    public void apagar(UUID usuarioId) {
        jdbc.sql("DELETE FROM atendimento.chave_gemini WHERE usuario_id = :usuario").param("usuario", usuarioId).update();
    }

    private static ChaveGemini chave(ResultSet rs, int linha) throws SQLException {
        ChaveCifrada cifrada = new ChaveCifrada(rs.getBytes("cifrada"), rs.getBytes("vetor_inicial"),
                rs.getShort("versao_chave_mestra"));
        return new ChaveGemini(rs.getObject("usuario_id", UUID.class), cifrada, rs.getString("ultimos_caracteres"),
                rs.getBoolean("nivel_pago_confirmado"), SituacaoDaChave.doCodigo(rs.getString("situacao")),
                instante(rs, "testada_em"), instante(rs, "atualizada_em"));
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime valor = rs.getObject(coluna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    private static OffsetDateTime utc(Instant instante) {
        return instante == null ? null : OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }
}
