package br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.ConsultaDeTexto;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Encontrado;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Pesquisa;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.TrechoDestacado;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.DadosDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.ImagemDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.MudancaDeSituacao;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.NovoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registros;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.SituacaoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.TipoDoRegistro;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * conhecimento.registro, registro_imagem e registro_situacao. A pesquisa usa a busca textual (português, sem acento,
 * com os pesos do gatilho) e, para o que a busca textual não acha, trigramas: pedaço do código da tela, mensagem de
 * erro parecida (uma palavra diferente) e pedaço do nome do cliente.
 */
@Repository
class RegistrosJdbc implements Registros {

    private static final String COLUNAS = """
            r.id, r.origem, r.atendimento_id, r.saida_id, r.autor_id, u.nome AS autor_nome, u.login AS autor_login, r.tipo,
            r.titulo, r.cliente, r.contato, r.modulo, r.tela_nome, r.tela_codigo, r.sintoma, r.mensagem_erro, r.causa,
            r.resolucao_texto, r.resolucao_html, r.situacao, r.chamado_dev, r.versao_correcao, r.solucao_validada,
            r.validada_por, r.validada_em, r.versao, r.criado_em, r.atualizado_em""";

    private static final String OPCOES_DO_TITULO = "StartSel=" + TrechoDestacado.INICIO + ", StopSel="
            + TrechoDestacado.FIM + ", HighlightAll=true";
    private static final String OPCOES_DO_TRECHO = "StartSel=" + TrechoDestacado.INICIO + ", StopSel="
            + TrechoDestacado.FIM + ", FragmentDelimiter=" + TrechoDestacado.ENTRE_FRAGMENTOS
            + ", MaxFragments=2, MaxWords=22, MinWords=8";

    private final JdbcClient jdbc;
    private final TransactionOperations transacao;

    RegistrosJdbc(JdbcClient jdbc, TransactionOperations transacao) {
        this.jdbc = jdbc;
        this.transacao = transacao;
    }

    @Override
    public void criar(NovoRegistro novo, List<ImagemDoRegistro> imagens) {
        transacao.executeWithoutResult(status -> {
            DadosDoRegistro d = novo.dados();
            jdbc.sql("""
                            INSERT INTO conhecimento.registro (id, origem, atendimento_id, saida_id, autor_id, tipo, titulo,
                                   cliente, contato, modulo, tela_nome, tela_codigo, sintoma, mensagem_erro, causa,
                                   resolucao_texto, resolucao_html, situacao, chamado_dev, criado_em, atualizado_em)
                            VALUES (:id, 'gerador', :atendimento, :saida, :autor, :tipo, :titulo, :cliente, :contato, :modulo,
                                   :telaNome, :telaCodigo, :sintoma, :erro, :causa, :resolucao, :html, :situacao, :chamado,
                                   :em, :em)
                            """)
                    .param("id", novo.id())
                    .param("atendimento", novo.atendimentoId())
                    .param("saida", novo.saidaId())
                    .param("autor", novo.autorId())
                    .params(dados(d))
                    .param("html", novo.resolucaoHtml())
                    .param("situacao", novo.situacao().codigo())
                    .param("chamado", novo.chamadoDev())
                    .param("em", utc(novo.criadoEm()))
                    .update();
            for (ImagemDoRegistro imagem : imagens) {
                jdbc.sql("""
                                INSERT INTO conhecimento.registro_imagem (registro_id, numero, caminho, legenda)
                                VALUES (:registro, :numero, :caminho, :legenda)
                                """)
                        .param("registro", novo.id())
                        .param("numero", imagem.numero())
                        .param("caminho", imagem.caminho())
                        .param("legenda", limitar(imagem.legenda(), 300))
                        .update();
            }
            historico(novo.id(), new MudancaDeSituacao(null, novo.situacao(), novo.chamadoDev(), null, novo.autorLogin(),
                    novo.criadoEm()));
        });
    }

    @Override
    public Optional<Registro> buscar(UUID id) {
        return jdbc.sql("SELECT " + COLUNAS + """
                         FROM conhecimento.registro r JOIN public.tb_usuario u ON u.id = r.autor_id
                        WHERE r.id = :id
                        """)
                .param("id", id)
                .query(RegistrosJdbc::registro)
                .optional();
    }

    @Override
    public Optional<UUID> daSaida(UUID saidaId) {
        return jdbc.sql("SELECT id FROM conhecimento.registro WHERE saida_id = :saida")
                .param("saida", saidaId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public boolean editar(UUID id, int versao, DadosDoRegistro dados, Instant agora) {
        return jdbc.sql("""
                        UPDATE conhecimento.registro
                           SET tipo = :tipo, titulo = :titulo, cliente = :cliente, contato = :contato, modulo = :modulo,
                               tela_nome = :telaNome, tela_codigo = :telaCodigo, sintoma = :sintoma, mensagem_erro = :erro,
                               causa = :causa, resolucao_texto = :resolucao, versao = versao + 1, atualizado_em = :agora
                         WHERE id = :id AND versao = :versao
                        """)
                .param("id", id)
                .param("versao", versao)
                .params(dados(dados))
                .param("agora", utc(agora))
                .update() == 1;
    }

    @Override
    public boolean mudarSituacao(UUID id, int versao, MudancaDeSituacao mudanca) {
        Boolean mudou = transacao.execute(status -> {
            int linhas = jdbc.sql("""
                            UPDATE conhecimento.registro
                               SET situacao = :situacao, chamado_dev = :chamado, versao_correcao = :correcao,
                                   versao = versao + 1, atualizado_em = :agora
                             WHERE id = :id AND versao = :versao
                            """)
                    .param("id", id)
                    .param("versao", versao)
                    .param("situacao", mudanca.para().codigo())
                    .param("chamado", mudanca.chamadoDev())
                    .param("correcao", mudanca.versaoCorrecao())
                    .param("agora", utc(mudanca.em()))
                    .update();
            if (linhas != 1) {
                return false;
            }
            historico(id, mudanca);
            return true;
        });
        return Boolean.TRUE.equals(mudou);
    }

    @Override
    public boolean validar(UUID id, int versao, boolean validada, String por, Instant agora) {
        return jdbc.sql("""
                        UPDATE conhecimento.registro
                           SET solucao_validada = :validada, validada_por = :por, validada_em = :em,
                               versao = versao + 1, atualizado_em = :agora
                         WHERE id = :id AND versao = :versao
                        """)
                .param("id", id)
                .param("versao", versao)
                .param("validada", validada)
                .param("por", validada ? por : null)
                .param("em", validada ? utc(agora) : null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("agora", utc(agora))
                .update() == 1;
    }

    @Override
    public boolean apagar(UUID id) {
        return jdbc.sql("DELETE FROM conhecimento.registro WHERE id = :id").param("id", id).update() == 1;
    }

    @Override
    public List<ImagemDoRegistro> imagens(UUID id) {
        return jdbc.sql("SELECT numero, caminho, legenda FROM conhecimento.registro_imagem WHERE registro_id = :id ORDER BY numero")
                .param("id", id)
                .query((rs, linha) -> new ImagemDoRegistro(rs.getInt("numero"), rs.getString("caminho"), rs.getString("legenda")))
                .list();
    }

    @Override
    public List<MudancaDeSituacao> historico(UUID id) {
        return jdbc.sql("""
                        SELECT de, para, chamado_dev, versao_correcao, por, em
                          FROM conhecimento.registro_situacao WHERE registro_id = :id ORDER BY em, id
                        """)
                .param("id", id)
                .query((rs, linha) -> new MudancaDeSituacao(situacao(rs.getString("de")), situacao(rs.getString("para")),
                        rs.getString("chamado_dev"), rs.getString("versao_correcao"), rs.getString("por"), instante(rs, "em")))
                .list();
    }

    @Override
    public Pagina pesquisar(Pesquisa pesquisa) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        String consulta = consulta(pesquisa.texto(), parametros);
        String onde = onde(pesquisa, parametros);
        String relevancia = pesquisa.texto().vazia() ? "0::float8" : """
                coalesce(ts_rank_cd(r.busca, c.todas), 0) * 2 + coalesce(ts_rank_cd(r.busca, c.qualquer), 0) * 0.5
                  + greatest(coalesce(word_similarity(c.termo, r.tela_codigo), 0),
                             coalesce(similarity(conhecimento.sem_acento(r.mensagem_erro), c.termo), 0),
                             coalesce(word_similarity(c.termo, conhecimento.sem_acento(r.cliente)), 0) * 0.5)""";
        long total = jdbc.sql(consulta + " SELECT count(*) FROM conhecimento.registro r CROSS JOIN c WHERE " + onde)
                .params(parametros)
                .query(Long.class)
                .single();
        if (total == 0) {
            return new Pagina(List.of(), 0);
        }
        parametros.put("tamanho", pesquisa.tamanho());
        parametros.put("deslocamento", pesquisa.deslocamento());
        parametros.put("opcoesDoTitulo", OPCOES_DO_TITULO);
        parametros.put("opcoesDoTrecho", OPCOES_DO_TRECHO);
        String sql = consulta + """
                , achados AS (
                    SELECT r.id, %s AS relevancia, r.criado_em
                      FROM conhecimento.registro r CROSS JOIN c
                     WHERE %s
                     ORDER BY relevancia DESC, r.criado_em DESC, r.id
                     LIMIT :tamanho OFFSET :deslocamento)
                SELECT %s, a.relevancia,
                       conhecimento.sem_acento(r.titulo) AS titulo_sa,
                       CASE WHEN c.qualquer IS NULL THEN NULL
                            ELSE ts_headline('pg_catalog.portuguese', conhecimento.sem_acento(r.titulo), c.qualquer, :opcoesDoTitulo)
                       END AS titulo_destacado,
                       t.texto AS trecho, conhecimento.sem_acento(t.texto) AS trecho_sa,
                       CASE WHEN c.qualquer IS NULL OR t.texto = '' THEN NULL
                            ELSE ts_headline('pg_catalog.portuguese', conhecimento.sem_acento(t.texto), c.qualquer, :opcoesDoTrecho)
                       END AS trecho_destacado
                  FROM achados a
                  JOIN conhecimento.registro r ON r.id = a.id
                  JOIN public.tb_usuario u ON u.id = r.autor_id
                 CROSS JOIN c
                 CROSS JOIN LATERAL (SELECT regexp_replace(concat_ws(' · ', r.sintoma, r.mensagem_erro, r.causa,
                                            r.resolucao_texto), '\\s+', ' ', 'g') AS texto) t
                 ORDER BY a.relevancia DESC, a.criado_em DESC, a.id
                """.formatted(relevancia, onde, COLUNAS);
        List<Encontrado> registros = jdbc.sql(sql)
                .params(parametros)
                .query((rs, linha) -> new Encontrado(registro(rs, linha), rs.getDouble("relevancia"),
                        new Encontrado.Destaque(rs.getString("titulo"), rs.getString("titulo_sa"), rs.getString("titulo_destacado")),
                        new Encontrado.Destaque(rs.getString("trecho"), rs.getString("trecho_sa"), rs.getString("trecho_destacado"))))
                .list();
        return new Pagina(registros, total);
    }

    @Override
    public List<Autor> autores() {
        return jdbc.sql("""
                        SELECT u.id, u.nome FROM public.tb_usuario u
                         WHERE EXISTS (SELECT 1 FROM conhecimento.registro r WHERE r.autor_id = u.id)
                         ORDER BY lower(u.nome), u.id
                        """)
                .query((rs, linha) -> new Autor(rs.getObject("id", UUID.class), rs.getString("nome")))
                .list();
    }

    // ------------------------------------------------------------------------------------------------- apoio

    /** O "WITH c AS (...)": as consultas da busca textual e o termo para os trigramas (nulos sem texto). */
    private static String consulta(ConsultaDeTexto texto, Map<String, Object> parametros) {
        if (texto.vazia()) {
            return "WITH c AS (SELECT NULL::tsquery AS todas, NULL::tsquery AS qualquer, ''::text AS termo)";
        }
        parametros.put("todas", texto.todas());
        parametros.put("qualquer", texto.qualquer());
        parametros.put("termo", texto.termo());
        return """
                WITH c AS (SELECT to_tsquery('pg_catalog.portuguese', :todas) AS todas,
                                  to_tsquery('pg_catalog.portuguese', :qualquer) AS qualquer,
                                  lower(conhecimento.sem_acento(CAST(:termo AS text))) AS termo)""";
    }

    private static String onde(Pesquisa pesquisa, Map<String, Object> parametros) {
        StringBuilder onde = new StringBuilder("TRUE");
        if (pesquisa.tipo() != null) {
            onde.append(" AND r.tipo = :tipo");
            parametros.put("tipo", pesquisa.tipo().codigo());
        }
        if (pesquisa.situacao() != null) {
            onde.append(" AND r.situacao = :situacao");
            parametros.put("situacao", pesquisa.situacao().codigo());
        }
        if (pesquisa.cliente() != null) {
            onde.append(" AND conhecimento.sem_acento(r.cliente) ILIKE '%' || conhecimento.sem_acento(CAST(:cliente AS text)) || '%'");
            parametros.put("cliente", paraLike(pesquisa.cliente()));
        }
        if (pesquisa.modulo() != null) {
            onde.append(" AND conhecimento.sem_acento(r.modulo) ILIKE '%' || conhecimento.sem_acento(CAST(:modulo AS text)) || '%'");
            parametros.put("modulo", paraLike(pesquisa.modulo()));
        }
        if (pesquisa.autorId() != null) {
            onde.append(" AND r.autor_id = :autor");
            parametros.put("autor", pesquisa.autorId());
        }
        if (pesquisa.desde() != null) {
            onde.append(" AND r.criado_em >= :desde");
            parametros.put("desde", utc(pesquisa.desde()));
        }
        if (pesquisa.ate() != null) {
            onde.append(" AND r.criado_em < :ate");
            parametros.put("ate", utc(pesquisa.ate()));
        }
        if (pesquisa.soValidadas()) {
            onde.append(" AND r.solucao_validada");
        }
        ConsultaDeTexto texto = pesquisa.texto();
        if (!texto.vazia()) {
            StringBuilder casa = new StringBuilder("r.busca @@ c.todas");
            List<String> pedacos = texto.pedacosDeCodigo();
            for (int i = 0; i < pedacos.size(); i++) {
                casa.append(" OR r.tela_codigo ILIKE :codigo").append(i);
                parametros.put("codigo" + i, "%" + pedacos.get(i) + "%"); // só letras e números: nada a escapar
            }
            if (texto.termo().length() >= 4) {
                casa.append(" OR conhecimento.sem_acento(r.mensagem_erro) % c.termo")
                        .append(" OR c.termo <% conhecimento.sem_acento(r.mensagem_erro)");
            }
            if (texto.termo().length() >= 3) {
                casa.append(" OR c.termo <% conhecimento.sem_acento(r.cliente)");
            }
            onde.append(" AND (").append(casa).append(')');
        }
        return onde.toString();
    }

    private static String paraLike(String valor) {
        return valor.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private void historico(UUID registroId, MudancaDeSituacao mudanca) {
        jdbc.sql("""
                        INSERT INTO conhecimento.registro_situacao (registro_id, de, para, chamado_dev, versao_correcao, por, em)
                        VALUES (:registro, :de, :para, :chamado, :correcao, :por, :em)
                        """)
                .param("registro", registroId)
                .param("de", mudanca.de() == null ? null : mudanca.de().codigo())
                .param("para", mudanca.para().codigo())
                .param("chamado", mudanca.chamadoDev())
                .param("correcao", mudanca.versaoCorrecao())
                .param("por", limitar(mudanca.por(), 60))
                .param("em", utc(mudanca.em()))
                .update();
    }

    private static Map<String, Object> dados(DadosDoRegistro d) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tipo", d.tipo().codigo());
        p.put("titulo", d.titulo());
        p.put("cliente", d.cliente());
        p.put("contato", d.contato());
        p.put("modulo", d.modulo());
        p.put("telaNome", d.telaNome());
        p.put("telaCodigo", d.telaCodigo());
        p.put("sintoma", d.sintoma());
        p.put("erro", d.mensagemErro());
        p.put("causa", d.causa());
        p.put("resolucao", d.resolucao());
        return p;
    }

    private static Registro registro(ResultSet rs, int linha) throws SQLException {
        DadosDoRegistro dados = new DadosDoRegistro(rs.getString("titulo"),
                TipoDoRegistro.doCodigo(rs.getString("tipo")).orElseThrow(), rs.getString("cliente"), rs.getString("contato"),
                rs.getString("modulo"), rs.getString("tela_nome"), rs.getString("tela_codigo"), rs.getString("sintoma"),
                rs.getString("mensagem_erro"), rs.getString("causa"), rs.getString("resolucao_texto"));
        return new Registro(rs.getObject("id", UUID.class), rs.getString("origem"), rs.getObject("atendimento_id", UUID.class),
                rs.getObject("saida_id", UUID.class), rs.getObject("autor_id", UUID.class), rs.getString("autor_nome"),
                rs.getString("autor_login"), dados, rs.getString("resolucao_html"), situacao(rs.getString("situacao")),
                rs.getString("chamado_dev"), rs.getString("versao_correcao"), rs.getBoolean("solucao_validada"),
                rs.getString("validada_por"), instante(rs, "validada_em"), rs.getInt("versao"), instante(rs, "criado_em"),
                instante(rs, "atualizado_em"));
    }

    private static SituacaoDoRegistro situacao(String codigo) {
        return codigo == null ? null : SituacaoDoRegistro.doCodigo(codigo).orElseThrow();
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime valor = rs.getObject(coluna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    private static OffsetDateTime utc(Instant instante) {
        return instante == null ? null : OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }

    private static String limitar(String texto, int maximo) {
        return texto == null || texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
