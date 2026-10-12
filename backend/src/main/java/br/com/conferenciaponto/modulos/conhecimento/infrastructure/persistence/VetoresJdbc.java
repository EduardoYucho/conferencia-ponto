package br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.Vetores;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** conhecimento.vetor: os valores em {@code REAL[]}, um vetor por registro e modelo. */
@Repository
class VetoresJdbc implements Vetores {

    private final JdbcClient jdbc;
    private final TransactionOperations transacao;

    VetoresJdbc(JdbcClient jdbc, TransactionOperations transacao) {
        this.jdbc = jdbc;
        this.transacao = transacao;
    }

    @Override
    public List<Guardado> doModelo(String modelo, int dimensao) {
        return jdbc.sql("SELECT registro_id, valores FROM conhecimento.vetor WHERE modelo = :modelo AND dimensao = :dimensao")
                .param("modelo", modelo)
                .param("dimensao", dimensao)
                .query((rs, linha) -> new Guardado(rs.getObject("registro_id", UUID.class), valores(rs)))
                .list();
    }

    @Override
    public List<Pendente> pendentes(String modelo, int dimensao, int limite) {
        return jdbc.sql("""
                        SELECT r.id, r.autor_id, r.titulo, r.sintoma, r.mensagem_erro, r.causa, r.resolucao_texto
                          FROM conhecimento.registro r
                         WHERE NOT EXISTS (SELECT 1 FROM conhecimento.vetor v
                                            WHERE v.registro_id = r.id AND v.modelo = :modelo AND v.dimensao = :dimensao)
                         ORDER BY r.criado_em, r.id
                         LIMIT :limite
                        """)
                .param("modelo", modelo)
                .param("dimensao", dimensao)
                .param("limite", limite)
                .query((rs, linha) -> new Pendente(rs.getObject("id", UUID.class), rs.getObject("autor_id", UUID.class),
                        rs.getString("titulo"), rs.getString("sintoma"), rs.getString("mensagem_erro"), rs.getString("causa"),
                        rs.getString("resolucao_texto")))
                .list();
    }

    @Override
    public long contarPendentes(String modelo, int dimensao) {
        return jdbc.sql("""
                        SELECT count(*) FROM conhecimento.registro r
                         WHERE NOT EXISTS (SELECT 1 FROM conhecimento.vetor v
                                            WHERE v.registro_id = r.id AND v.modelo = :modelo AND v.dimensao = :dimensao)
                        """)
                .param("modelo", modelo)
                .param("dimensao", dimensao)
                .query(Long.class)
                .single();
    }

    @Override
    public void salvar(UUID registroId, String modelo, int dimensao, float[] valores, String textoSha256, Instant agora) {
        Float[] caixas = new Float[valores.length];
        for (int i = 0; i < valores.length; i++) {
            caixas[i] = valores[i];
        }
        transacao.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM conhecimento.vetor WHERE registro_id = :registro AND modelo <> :modelo")
                    .param("registro", registroId)
                    .param("modelo", modelo)
                    .update();
            jdbc.sql("""
                            INSERT INTO conhecimento.vetor (registro_id, modelo, dimensao, valores, texto_sha256, criado_em)
                            SELECT :registro, :modelo, :dimensao, CAST(:valores AS real[]), :sha, :agora
                             WHERE EXISTS (SELECT 1 FROM conhecimento.registro WHERE id = :registro)
                            ON CONFLICT (registro_id, modelo) DO UPDATE SET
                                dimensao = EXCLUDED.dimensao, valores = EXCLUDED.valores,
                                texto_sha256 = EXCLUDED.texto_sha256, criado_em = EXCLUDED.criado_em
                            """)
                    .param("registro", registroId)
                    .param("modelo", modelo)
                    .param("dimensao", dimensao)
                    .param("valores", literal(caixas))
                    .param("sha", textoSha256)
                    .param("agora", OffsetDateTime.ofInstant(agora, ZoneOffset.UTC))
                    .update();
        });
    }

    @Override
    public boolean invalidarSeMudou(UUID registroId, String textoSha256) {
        return jdbc.sql("DELETE FROM conhecimento.vetor WHERE registro_id = :registro AND texto_sha256 <> :sha")
                .param("registro", registroId)
                .param("sha", textoSha256)
                .update() > 0;
    }

    /** O array no formato de texto do PostgreSQL ({1.5,-0.25,...}): os valores são números, nada a escapar. */
    private static String literal(Float[] valores) {
        StringBuilder texto = new StringBuilder(valores.length * 12).append('{');
        for (int i = 0; i < valores.length; i++) {
            if (i > 0) {
                texto.append(',');
            }
            texto.append(Float.toString(valores[i]));
        }
        return texto.append('}').toString();
    }

    private static float[] valores(ResultSet rs) throws SQLException {
        Array array = rs.getArray("valores");
        Object[] lidos = (Object[]) array.getArray();
        float[] valores = new float[lidos.length];
        for (int i = 0; i < lidos.length; i++) {
            valores[i] = ((Number) lidos[i]).floatValue();
        }
        array.free();
        return valores;
    }
}
