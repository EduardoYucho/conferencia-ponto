package br.com.conferenciaponto.modulos.fiscal.infrastructure.persistence;

import br.com.conferenciaponto.modulos.fiscal.domain.Alvo;
import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.EstadoAtual;
import br.com.conferenciaponto.modulos.fiscal.domain.Incidente;
import br.com.conferenciaponto.modulos.fiscal.domain.Leitura;
import br.com.conferenciaponto.modulos.fiscal.domain.Mudanca;
import br.com.conferenciaponto.modulos.fiscal.domain.Origem;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.domain.Situacao;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** O schema fiscal (V18). Uma rodada grava tudo numa transação só. */
@Repository
public class RegistroDoMonitorJdbc implements RegistroDoMonitor {

    private final JdbcClient jdbc;
    private final TransactionOperations transacao;

    public RegistroDoMonitorJdbc(JdbcClient jdbc, TransactionOperations transacao) {
        this.jdbc = jdbc;
        this.transacao = transacao;
    }

    @Override
    public Map<String, EstadoAtual> estados() {
        Map<String, EstadoAtual> estados = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT chave, documento, autorizador, servico, origem, situacao, detalhe, cstat, tempo_ms,
                               verificado_em, lida_em, desde, ruins_seguidas
                          FROM fiscal.situacao_atual ORDER BY chave""")
                .query((rs, n) -> new EstadoAtual(new Leitura(alvo(rs), Origem.doCodigo(rs.getString("origem")),
                        Situacao.valueOf(rs.getString("situacao")), rs.getString("detalhe"), inteiro(rs, "cstat"),
                        inteiro(rs, "tempo_ms"), instante(rs, "verificado_em")), instante(rs, "lida_em"),
                        instante(rs, "desde"), rs.getInt("ruins_seguidas")))
                .list()
                .forEach(e -> estados.put(e.alvo().chave(), e));
        return estados;
    }

    @Override
    public Map<String, Incidente> incidentesAbertos() {
        Map<String, Incidente> abertos = new LinkedHashMap<>();
        jdbc.sql(SELECT_INCIDENTE + " WHERE fim IS NULL").query(RegistroDoMonitorJdbc::incidente).list()
                .forEach(i -> abertos.put(i.alvo().chave(), i));
        return abertos;
    }

    @Override
    public void gravar(RodadaConcluida rodada) {
        transacao.executeWithoutResult(status -> {
            rodada.estados().forEach(this::gravarEstado);
            rodada.mudancas().forEach(this::gravarMudanca);
            rodada.incidentes().forEach(this::gravarIncidente);
            Instant horaDaRodada = rodada.rodada().inicio().truncatedTo(ChronoUnit.HOURS);
            rodada.gerais().forEach(l -> somarNaHora(l, horaDaRodada));
            rodada.fontes().forEach(this::gravarFonte);
            rodada.avisos().forEach(this::gravarAviso);
            Rodada r = rodada.rodada();
            jdbc.sql("""
                            INSERT INTO fiscal.rodada (inicio, fim, leituras, fontes_com_problema, sem_internet)
                            VALUES (:inicio, :fim, :leituras, :fontes, :semInternet)""")
                    .param("inicio", hora(r.inicio())).param("fim", hora(r.fim())).param("leituras", r.leituras())
                    .param("fontes", r.fontesComProblema()).param("semInternet", r.semInternet())
                    .update();
        });
    }

    @Override
    public List<Incidente> incidentesDesde(Instant desde) {
        return jdbc.sql(SELECT_INCIDENTE + "\n" + """
                         WHERE inicio >= :desde OR fim IS NULL OR fim >= :desde
                         ORDER BY inicio DESC, id DESC LIMIT 1000""")
                .param("desde", hora(desde))
                .query(RegistroDoMonitorJdbc::incidente)
                .list();
    }

    @Override
    public List<Disponibilidade> disponibilidadeDesde(Instant desde) {
        return jdbc.sql("""
                        SELECT documento, autorizador, SUM(leituras) AS leituras, SUM(ruins) AS ruins,
                               SUM(sem_informacao) AS sem_informacao,
                               (SUM(tempo_total_ms) / NULLIF(SUM(tempos), 0))::INTEGER AS tempo_medio,
                               MAX(tempo_max_ms) AS tempo_max
                          FROM fiscal.resumo_hora
                         WHERE hora >= date_trunc('hour', CAST(:desde AS TIMESTAMPTZ))
                         GROUP BY documento, autorizador
                         ORDER BY documento, autorizador""")
                .param("desde", hora(desde))
                .query((rs, n) -> new Disponibilidade(Documento.valueOf(rs.getString("documento")),
                        rs.getString("autorizador"), rs.getInt("leituras"), rs.getInt("ruins"),
                        rs.getInt("sem_informacao"), inteiro(rs, "tempo_medio"), inteiro(rs, "tempo_max")))
                .list();
    }

    @Override
    public Optional<Rodada> ultimaRodada() {
        return jdbc.sql("""
                        SELECT inicio, fim, leituras, fontes_com_problema, sem_internet
                          FROM fiscal.rodada ORDER BY inicio DESC, id DESC LIMIT 1""")
                .query((rs, n) -> new Rodada(instante(rs, "inicio"), instante(rs, "fim"), rs.getInt("leituras"),
                        rs.getInt("fontes_com_problema"), rs.getBoolean("sem_internet")))
                .optional();
    }

    @Override
    public List<SituacaoDaFonte> fontes() {
        return jdbc.sql("SELECT nome, ultima_tentativa, ultimo_sucesso, erro FROM fiscal.fonte ORDER BY nome")
                .query((rs, n) -> new SituacaoDaFonte(rs.getString("nome"), instante(rs, "ultima_tentativa"),
                        instante(rs, "ultimo_sucesso"), rs.getString("erro")))
                .list();
    }

    @Override
    public List<Aviso> avisos() {
        return jdbc.sql("SELECT documento, texto, desde, visto_em FROM fiscal.aviso ORDER BY documento")
                .query((rs, n) -> new Aviso(Documento.valueOf(rs.getString("documento")), rs.getString("texto"),
                        instante(rs, "desde"), instante(rs, "visto_em")))
                .list();
    }

    @Override
    public void limpar(Instant resumosAntesDe, Instant mudancasAntesDe, Instant rodadasAntesDe) {
        transacao.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM fiscal.resumo_hora WHERE hora < :antes").param("antes", hora(resumosAntesDe)).update();
            jdbc.sql("DELETE FROM fiscal.mudanca WHERE em < :antes").param("antes", hora(mudancasAntesDe)).update();
            jdbc.sql("DELETE FROM fiscal.rodada WHERE inicio < :antes").param("antes", hora(rodadasAntesDe)).update();
            // um alvo que nenhuma fonte traz há dias (saiu do painel ou da configuração) deixa de aparecer
            jdbc.sql("DELETE FROM fiscal.situacao_atual WHERE situacao = 'SEM_INFORMACAO' AND desde < :antes")
                    .param("antes", hora(rodadasAntesDe)).update();
        });
    }

    private void gravarEstado(EstadoAtual e) {
        Leitura l = e.leitura();
        jdbc.sql("""
                        INSERT INTO fiscal.situacao_atual (chave, documento, autorizador, servico, origem, situacao, detalhe,
                                                           cstat, tempo_ms, verificado_em, lida_em, desde, ruins_seguidas)
                        VALUES (:chave, :documento, :autorizador, :servico, :origem, :situacao, :detalhe, :cstat, :tempo,
                                :verificado, :lida, :desde, :ruins)
                        ON CONFLICT (chave) DO UPDATE SET
                            origem = EXCLUDED.origem, situacao = EXCLUDED.situacao, detalhe = EXCLUDED.detalhe,
                            cstat = EXCLUDED.cstat, tempo_ms = EXCLUDED.tempo_ms, verificado_em = EXCLUDED.verificado_em,
                            lida_em = EXCLUDED.lida_em, desde = EXCLUDED.desde, ruins_seguidas = EXCLUDED.ruins_seguidas""")
                .param("chave", l.alvo().chave()).param("documento", l.alvo().documento().name())
                .param("autorizador", l.alvo().autorizador()).param("servico", l.alvo().servico())
                .param("origem", l.origem().codigo()).param("situacao", l.situacao().name())
                .param("detalhe", l.detalhe()).param("cstat", l.cstat()).param("tempo", l.tempoMs())
                .param("verificado", hora(l.verificadoEm())).param("lida", hora(e.lidaEm()))
                .param("desde", hora(e.desde())).param("ruins", e.ruinsSeguidas())
                .update();
    }

    private void gravarMudanca(Mudanca m) {
        jdbc.sql("""
                        INSERT INTO fiscal.mudanca (chave, documento, autorizador, servico, de, para, detalhe, em)
                        VALUES (:chave, :documento, :autorizador, :servico, :de, :para, :detalhe, :em)""")
                .param("chave", m.alvo().chave()).param("documento", m.alvo().documento().name())
                .param("autorizador", m.alvo().autorizador()).param("servico", m.alvo().servico())
                .param("de", m.de() == null ? null : m.de().name()).param("para", m.para().name())
                .param("detalhe", m.detalhe()).param("em", hora(m.em()))
                .update();
    }

    private void gravarIncidente(Incidente i) {
        if (i.id() == null) {
            jdbc.sql("""
                            INSERT INTO fiscal.incidente (chave, documento, autorizador, inicio, fim, pior, detalhe, leituras)
                            VALUES (:chave, :documento, :autorizador, :inicio, :fim, :pior, :detalhe, :leituras)""")
                    .param("chave", i.alvo().chave()).param("documento", i.alvo().documento().name())
                    .param("autorizador", i.alvo().autorizador()).param("inicio", hora(i.inicio()))
                    .param("fim", hora(i.fim())).param("pior", i.pior().name()).param("detalhe", i.detalhe())
                    .param("leituras", i.leituras())
                    .update();
        } else {
            jdbc.sql("""
                            UPDATE fiscal.incidente SET fim = :fim, pior = :pior, detalhe = :detalhe, leituras = :leituras
                             WHERE id = :id""")
                    .param("fim", hora(i.fim())).param("pior", i.pior().name()).param("detalhe", i.detalhe())
                    .param("leituras", i.leituras()).param("id", i.id())
                    .update();
        }
    }

    private void somarNaHora(Leitura l, Instant horaDaRodada) {
        Situacao s = l.situacao();
        jdbc.sql("""
                        INSERT INTO fiscal.resumo_hora (chave, hora, documento, autorizador, leituras, ruins, sem_informacao,
                                                        tempo_total_ms, tempos, tempo_max_ms)
                        VALUES (:chave, :hora, :documento, :autorizador, 1, :ruim, :semInfo, :tempo, :comTempo, :tempoMax)
                        ON CONFLICT (chave, hora) DO UPDATE SET
                            leituras = fiscal.resumo_hora.leituras + 1,
                            ruins = fiscal.resumo_hora.ruins + EXCLUDED.ruins,
                            sem_informacao = fiscal.resumo_hora.sem_informacao + EXCLUDED.sem_informacao,
                            tempo_total_ms = fiscal.resumo_hora.tempo_total_ms + EXCLUDED.tempo_total_ms,
                            tempos = fiscal.resumo_hora.tempos + EXCLUDED.tempos,
                            tempo_max_ms = GREATEST(fiscal.resumo_hora.tempo_max_ms, EXCLUDED.tempo_max_ms)""")
                .param("chave", l.alvo().chave())
                .param("hora", hora(horaDaRodada))
                .param("documento", l.alvo().documento().name()).param("autorizador", l.alvo().autorizador())
                .param("ruim", s.ruim() ? 1 : 0).param("semInfo", s == Situacao.SEM_INFORMACAO ? 1 : 0)
                .param("tempo", l.tempoMs() == null ? 0L : l.tempoMs().longValue())
                .param("comTempo", l.tempoMs() == null ? 0 : 1).param("tempoMax", l.tempoMs())
                .update();
    }

    private void gravarFonte(SituacaoDaFonte f) {
        jdbc.sql("""
                        INSERT INTO fiscal.fonte (nome, ultima_tentativa, ultimo_sucesso, erro)
                        VALUES (:nome, :tentativa, :sucesso, :erro)
                        ON CONFLICT (nome) DO UPDATE SET
                            ultima_tentativa = EXCLUDED.ultima_tentativa,
                            ultimo_sucesso = COALESCE(EXCLUDED.ultimo_sucesso, fiscal.fonte.ultimo_sucesso),
                            erro = EXCLUDED.erro""")
                .param("nome", f.nome()).param("tentativa", hora(f.ultimaTentativa()))
                .param("sucesso", hora(f.ultimoSucesso())).param("erro", cortar(f.erro(), 300))
                .update();
    }

    private void gravarAviso(Aviso a) {
        if (a.texto() == null || a.texto().isBlank()) {
            jdbc.sql("DELETE FROM fiscal.aviso WHERE documento = :documento").param("documento", a.documento().name())
                    .update();
            return;
        }
        jdbc.sql("""
                        INSERT INTO fiscal.aviso (documento, texto, desde, visto_em)
                        VALUES (:documento, :texto, :desde, :visto)
                        ON CONFLICT (documento) DO UPDATE SET
                            desde = CASE WHEN fiscal.aviso.texto = EXCLUDED.texto THEN fiscal.aviso.desde
                                         ELSE EXCLUDED.desde END,
                            texto = EXCLUDED.texto, visto_em = EXCLUDED.visto_em""")
                .param("documento", a.documento().name()).param("texto", cortar(a.texto(), 500))
                .param("desde", hora(a.desde())).param("visto", hora(a.vistoEm()))
                .update();
    }

    private static final String SELECT_INCIDENTE = """
            SELECT id, chave, documento, autorizador, inicio, fim, pior, detalhe, leituras FROM fiscal.incidente""";

    private static Incidente incidente(ResultSet rs, int n) throws SQLException {
        return new Incidente(rs.getLong("id"), Alvo.geral(Documento.valueOf(rs.getString("documento")),
                rs.getString("autorizador")), instante(rs, "inicio"), instante(rs, "fim"),
                Situacao.valueOf(rs.getString("pior")), rs.getString("detalhe"), rs.getInt("leituras"));
    }

    private static Alvo alvo(ResultSet rs) throws SQLException {
        return new Alvo(Documento.valueOf(rs.getString("documento")), rs.getString("autorizador"),
                rs.getString("servico"));
    }

    private static Integer inteiro(ResultSet rs, String coluna) throws SQLException {
        int valor = rs.getInt(coluna);
        return rs.wasNull() ? null : valor;
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime valor = rs.getObject(coluna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    private static OffsetDateTime hora(Instant instante) {
        return instante == null ? null : instante.atOffset(ZoneOffset.UTC);
    }

    private static String cortar(String texto, int maximo) {
        if (texto == null) {
            return null;
        }
        String limpo = texto.strip();
        return limpo.length() <= maximo ? limpo : limpo.substring(0, maximo - 1) + "…";
    }
}
