package br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.ConsultaDeTexto;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Encontrado;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Pesquisa;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Trecho;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.TrechoDestacado;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.DadosDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.ImagemDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.MudancaDeSituacao;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.NovoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registros;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.SituacaoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.TipoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.Semelhante;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os registros num PostgreSQL de verdade (temporário): gravação, versão, histórico e a pesquisa (com e sem acento,
 * pedaço de código, erro com uma palavra diferente, filtros e paginação). Cada teste filtra pelo próprio autor, porque
 * o banco é compartilhado pelos testes.
 */
class RegistrosJdbcTest {

    private static final Instant AGORA = Instant.parse("2026-10-12T15:00:00Z");

    private final Registros registros = RepositoriosDaBase.registros();
    private final JdbcClient jdbc = PostgresDeTeste.jdbc();
    private UUID autor;

    @BeforeEach
    void preparar() {
        autor = PostgresDeTeste.novoUsuario(true);
    }

    private UUID criar(DadosDoRegistro dados, SituacaoDoRegistro situacao, Instant em, ImagemDoRegistro... imagens) {
        UUID id = UUID.randomUUID();
        registros.criar(new NovoRegistro(id, UUID.randomUUID(), UUID.randomUUID(), autor, "maria", dados, "<p>Texto</p>",
                situacao, null, em), List.of(imagens));
        return id;
    }

    private static DadosDoRegistro dados(String titulo, TipoDoRegistro tipo, String cliente, String modulo, String tela,
                                         String codigo, String sintoma, String erro, String causa, String resolucao) {
        return new DadosDoRegistro(titulo, tipo, cliente, "Contato", modulo, tela, codigo, sintoma, erro, causa, resolucao);
    }

    private DadosDoRegistro pedido() {
        return dados("Erro ao gravar pedido com estoque negativo", TipoDoRegistro.ERRO, "Padaria São João", "Vendas > Pedidos",
                "Pedidos de venda", "EQR76", "Ao gravar o pedido de venda aparece a mensagem de estoque.",
                "Não foi possível gravar o pedido: estoque insuficiente para o produto", "O produto estava com estoque "
                        + "negativo no depósito.", "Acertar o estoque pelo inventário e gravar de novo.");
    }

    private DadosDoRegistro configuracao() {
        return dados("Configuração de usuários não abre", TipoDoRegistro.DUVIDA, "Mercado Bom Preço", "Configurações",
                "Usuários", "CFG002", "Ao abrir a configuração dos usuários o sistema avisa que não tem permissão.",
                "Usuário sem permissão para acessar a configuração", "Perfil sem a permissão de administrador.",
                "Dar a permissão no perfil do usuário.");
    }

    private Pesquisa pesquisa(String texto) {
        return new Pesquisa(ConsultaDeTexto.de(texto), null, null, null, null, autor, null, null, false, 1, 20);
    }

    private List<UUID> achados(Pesquisa pesquisa) {
        return registros.pesquisar(pesquisa).registros().stream().map(e -> e.registro().id()).toList();
    }

    @Test
    void gravaOsCamposAsImagensEAPrimeiraLinhaDoHistorico() {
        UUID id = criar(pedido(), SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO, AGORA,
                new ImagemDoRegistro(1, "base/x/Imagem_01.png", "tela de pedidos"),
                new ImagemDoRegistro(2, "base/x/Imagem_02.jpeg", null));

        Registro registro = registros.buscar(id).orElseThrow();
        assertThat(registro.dados()).isEqualTo(pedido());
        assertThat(registro.origem()).isEqualTo("gerador");
        assertThat(registro.autorId()).isEqualTo(autor);
        assertThat(registro.autorNome()).isEqualTo("Pessoa de teste");
        assertThat(registro.situacao()).isEqualTo(SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO);
        assertThat(registro.versao()).isZero();
        assertThat(registro.criadoEm()).isEqualTo(AGORA);
        assertThat(registros.daSaida(registro.saidaId())).contains(id);
        assertThat(registros.imagens(id)).extracting(ImagemDoRegistro::numero).containsExactly(1, 2);
        assertThat(registros.historico(id)).singleElement().satisfies(m -> {
            assertThat(m.de()).isNull();
            assertThat(m.para()).isEqualTo(SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO);
            assertThat(m.por()).isEqualTo("maria");
        });
    }

    @Test
    void edicaoComAVersaoQueAPessoaViu() {
        UUID id = criar(pedido(), SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO, AGORA);
        DadosDoRegistro novo = dados("Outro título", TipoDoRegistro.ERRO, null, null, null, null, null, null, null, null);

        assertThat(registros.editar(id, 0, novo, AGORA.plusSeconds(60))).isTrue();
        assertThat(registros.editar(id, 0, pedido(), AGORA.plusSeconds(120))).as("versão antiga").isFalse();

        Registro editado = registros.buscar(id).orElseThrow();
        assertThat(editado.dados()).isEqualTo(novo);
        assertThat(editado.versao()).isEqualTo(1);
        assertThat(editado.atualizadoEm()).isEqualTo(AGORA.plusSeconds(60));
    }

    @Test
    void situacaoComHistoricoEValidacao() {
        UUID id = criar(pedido(), SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO, AGORA);

        assertThat(registros.mudarSituacao(id, 0, new MudancaDeSituacao(SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO,
                SituacaoDoRegistro.CORRIGIDO, "OTRS 123", "5.2.1", "joao", AGORA.plusSeconds(60)))).isTrue();
        assertThat(registros.mudarSituacao(id, 0, new MudancaDeSituacao(SituacaoDoRegistro.CORRIGIDO,
                SituacaoDoRegistro.NAO_SERA_FEITO, null, null, "ana", AGORA.plusSeconds(90)))).as("versão antiga").isFalse();
        assertThat(registros.validar(id, 1, true, "joao", AGORA.plusSeconds(120))).isTrue();

        Registro registro = registros.buscar(id).orElseThrow();
        assertThat(registro.situacao()).isEqualTo(SituacaoDoRegistro.CORRIGIDO);
        assertThat(registro.chamadoDev()).isEqualTo("OTRS 123");
        assertThat(registro.versaoCorrecao()).isEqualTo("5.2.1");
        assertThat(registro.solucaoValidada()).isTrue();
        assertThat(registro.validadaPor()).isEqualTo("joao");
        assertThat(registro.versao()).isEqualTo(2);
        assertThat(registros.historico(id)).extracting(MudancaDeSituacao::para)
                .containsExactly(SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO, SituacaoDoRegistro.CORRIGIDO);

        assertThat(registros.validar(id, 2, false, "joao", AGORA.plusSeconds(180))).isTrue();
        assertThat(registros.buscar(id).orElseThrow().validadaPor()).isNull();
    }

    @Test
    void apagarLevaAsImagensEOHistorico() {
        UUID id = criar(pedido(), SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA, new ImagemDoRegistro(1, "base/x/Imagem_01.png", null));

        assertThat(registros.apagar(id)).isTrue();
        assertThat(registros.buscar(id)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM conhecimento.registro_imagem WHERE registro_id = ?").param(id)
                .query(Long.class).single()).isZero();
        assertThat(registros.historico(id)).isEmpty();
        assertThat(registros.apagar(id)).isFalse();
    }

    @Test
    void comESemAcento() {
        UUID config = criar(configuracao(), SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA);
        UUID pedido = criar(pedido(), SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO, AGORA);

        assertThat(achados(pesquisa("configuração"))).containsExactly(config);
        assertThat(achados(pesquisa("configuracao"))).containsExactly(config);
        assertThat(achados(pesquisa("CONFIGURAÇÕES usuario"))).containsExactly(config);
        assertThat(achados(pesquisa("deposito inventario"))).containsExactly(pedido);
        assertThat(achados(pesquisa("padaria sao joao"))).containsExactly(pedido);
        assertThat(achados(pesquisa("nada disso existe aqui"))).isEmpty();
    }

    @Test
    void pedacoDoCodigoDaTela() {
        UUID pedido = criar(pedido(), SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO, AGORA);
        criar(configuracao(), SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA);

        assertThat(achados(pesquisa("eqr7"))).containsExactly(pedido);
        assertThat(achados(pesquisa("EQR76"))).containsExactly(pedido);
    }

    @Test
    void erroComUmaPalavraDiferente() {
        UUID pedido = criar(pedido(), SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO, AGORA);
        criar(configuracao(), SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA);

        assertThat(achados(pesquisa("Não foi possível salvar o pedido: estoque insuficiente para o produto")))
                .containsExactly(pedido);
    }

    @Test
    void oQueMaisBateVemPrimeiroComOTrechoDestacado() {
        UUID pouco = criar(dados("Pedido lento", TipoDoRegistro.DUVIDA, null, null, null, null, "A tela demora.", null, null,
                "Falar sobre o estoque."), SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA.plusSeconds(60));
        UUID muito = criar(pedido(), SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO, AGORA);

        Registros.Pagina pagina = registros.pesquisar(pesquisa("estoque pedido"));

        assertThat(pagina.registros()).extracting(e -> e.registro().id()).containsExactly(muito, pouco);
        Encontrado primeiro = pagina.registros().get(0);
        Trecho titulo = TrechoDestacado.de(primeiro.titulo().original(), primeiro.titulo().semAcento(),
                primeiro.titulo().destacado());
        assertThat(titulo.partes()).filteredOn(Trecho.Parte::destaque).extracting(Trecho.Parte::texto)
                .containsExactly("pedido", "estoque");
        Trecho trecho = TrechoDestacado.de(primeiro.trecho().original(), primeiro.trecho().semAcento(),
                primeiro.trecho().destacado());
        assertThat(trecho.comDestaque()).isTrue();
        assertThat(trecho.partes()).filteredOn(Trecho.Parte::destaque).extracting(Trecho.Parte::texto)
                .allMatch(t -> t.equals("pedido") || t.equals("estoque"));
    }

    @Test
    void oTrechoVoltaComOsAcentos() {
        criar(dados("Nota fiscal", TipoDoRegistro.ERRO, null, null, null, null, "A emissão da nota não sai no caixa.", null,
                null, null), SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA);

        Encontrado achado = registros.pesquisar(pesquisa("emissao")).registros().get(0);
        Trecho trecho = TrechoDestacado.de(achado.trecho().original(), achado.trecho().semAcento(), achado.trecho().destacado());

        assertThat(trecho.partes()).contains(new Trecho.Parte("emissão", true));
        assertThat(trecho.texto()).contains("não sai");
    }

    @Test
    void filtrosSemTexto() {
        UUID config = criar(configuracao(), SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA.minusSeconds(86_400 * 3));
        UUID pedido = criar(pedido(), SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO, AGORA);
        registros.validar(config, 0, true, "joao", AGORA);

        assertThat(achados(pesquisa(null))).as("do mais novo para o mais antigo").containsExactly(pedido, config);
        assertThat(achados(filtro(TipoDoRegistro.ERRO, null, null, null, null, null, false))).containsExactly(pedido);
        assertThat(achados(filtro(null, SituacaoDoRegistro.RESOLVIDO_SUPORTE, null, null, null, null, false)))
                .containsExactly(config);
        assertThat(achados(filtro(null, null, "sao joao", null, null, null, false))).containsExactly(pedido);
        assertThat(achados(filtro(null, null, null, "configuracoes", null, null, false))).containsExactly(config);
        assertThat(achados(filtro(null, null, null, null, AGORA.minusSeconds(86_400), null, false))).containsExactly(pedido);
        assertThat(achados(filtro(null, null, null, null, null, AGORA.minusSeconds(86_400), false))).containsExactly(config);
        assertThat(achados(filtro(null, null, null, null, null, null, true))).containsExactly(config);
        assertThat(achados(filtro(null, null, "50%_\\", null, null, null, false))).isEmpty();

        UUID outroAutor = PostgresDeTeste.novoUsuario(true);
        assertThat(achados(new Pesquisa(null, null, null, null, null, outroAutor, null, null, false, 1, 20))).isEmpty();
    }

    private Pesquisa filtro(TipoDoRegistro tipo, SituacaoDoRegistro situacao, String cliente, String modulo, Instant desde,
                            Instant ate, boolean validadas) {
        return new Pesquisa(null, tipo, situacao, cliente, modulo, autor, desde, ate, validadas, 1, 20);
    }

    @Test
    void paginacao() {
        for (int i = 0; i < 5; i++) {
            criar(dados("Caso " + i, TipoDoRegistro.DUVIDA, null, null, null, null, null, null, null, null),
                    SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA.plusSeconds(i));
        }
        Registros.Pagina segunda = registros.pesquisar(new Pesquisa(null, null, null, null, null, autor, null, null, false, 2, 2));

        assertThat(segunda.total()).isEqualTo(5);
        assertThat(segunda.registros()).extracting(e -> e.registro().dados().titulo()).containsExactly("Caso 2", "Caso 1");
        assertThat(registros.pesquisar(new Pesquisa(null, null, null, null, null, autor, null, null, false, 4, 2))
                .registros()).isEmpty();
        assertThat(registros.pesquisar(new Pesquisa(null, null, null, null, null, autor, null, null, false, 200_000_000, 20))
                .registros()).as("página absurda: vazia, sem estourar o OFFSET").isEmpty();
    }

    @Test
    void autoresDaBase() {
        criar(pedido(), SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA);

        assertThat(registros.autores()).extracting(Registros.Autor::id).contains(autor);
    }

    @Test
    void fusaoDaPesquisaExataComAPorSignificado() {
        UUID soTexto = criar(dados("Estoque negativo", TipoDoRegistro.ERRO, null, null, null, null, "O estoque fica negativo.",
                null, null, null), SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA);
        UUID nasDuas = criar(dados("Estoque some do depósito", TipoDoRegistro.ERRO, null, null, null, null, "Estoque some.",
                null, null, null), SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA.plusSeconds(1));
        UUID soSignificado = criar(dados("Saldo do produto errado", TipoDoRegistro.DUVIDA, null, null, null, null,
                "O saldo aparece menor.", null, null, null), SituacaoDoRegistro.RESOLVIDO_SUPORTE, AGORA.plusSeconds(2));
        UUID foraDoFiltro = criar(dados("Saldo zerado", TipoDoRegistro.DUVIDA, null, null, null, null, null, null, null, null),
                SituacaoDoRegistro.CORRIGIDO, AGORA);
        List<Semelhante> significado = List.of(new Semelhante(soSignificado, 0.91), new Semelhante(nasDuas, 0.88),
                new Semelhante(foraDoFiltro, 0.80));

        Registros.Pagina pagina = registros.pesquisar(new Pesquisa(ConsultaDeTexto.de("estoque"), null,
                SituacaoDoRegistro.RESOLVIDO_SUPORTE, null, null, autor, null, null, false, 1, 20, significado));

        assertThat(pagina.total()).isEqualTo(3);
        assertThat(pagina.registros()).extracting(e -> e.registro().id()).first().isEqualTo(nasDuas);
        assertThat(pagina.registros()).extracting(e -> e.registro().id()).containsExactlyInAnyOrder(nasDuas, soTexto, soSignificado)
                .doesNotContain(foraDoFiltro);
        Encontrado peloSignificado = pagina.registros().stream().filter(e -> e.registro().id().equals(soSignificado))
                .findFirst().orElseThrow();
        assertThat(peloSignificado.textual()).isFalse();
        assertThat(peloSignificado.similaridade()).isEqualTo(0.91);
        Encontrado peloTexto = pagina.registros().stream().filter(e -> e.registro().id().equals(soTexto)).findFirst().orElseThrow();
        assertThat(peloTexto.textual()).isTrue();
        assertThat(peloTexto.similaridade()).isNull();
    }

    @Test
    void resumosEMesmaTela() {
        String codigo = "Z" + UUID.randomUUID().toString().substring(0, 6);
        UUID id = criar(dados("Erro na tela", TipoDoRegistro.ERRO, null, null, null, codigo, null, null, null, null),
                SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO, AGORA);

        assertThat(registros.comCodigoDeTela(" " + codigo.toLowerCase() + " ", 5)).containsExactly(id);
        assertThat(registros.comCodigoDeTela(null, 5)).isEmpty();
        assertThat(registros.resumos(List.of(id))).singleElement().satisfies(r -> {
            assertThat(r.titulo()).isEqualTo("Erro na tela");
            assertThat(r.situacao()).isEqualTo(SituacaoDoRegistro.ENVIADO_DESENVOLVIMENTO);
            assertThat(r.telaCodigo()).isEqualTo(codigo);
        });
        assertThat(registros.resumos(List.of())).isEmpty();
    }
}
