package br.com.conferenciaponto.modulos.conhecimento.domain.registro;

import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Encontrado;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Pesquisa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Os registros da base (schema conhecimento). As mudanças levam a versão que a pessoa viu: se outra pessoa mudou
 * antes, nada muda e a resposta é false.
 */
public interface Registros {

    /** Cria o registro, as imagens e a primeira linha do histórico (situação inicial), numa transação. */
    void criar(NovoRegistro novo, List<ImagemDoRegistro> imagens);

    Optional<Registro> buscar(UUID id);

    /** O registro criado a partir de uma saída confirmada. */
    Optional<UUID> daSaida(UUID saidaId);

    boolean editar(UUID id, int versao, DadosDoRegistro dados, Instant agora);

    /** Muda a situação (e o chamado e a versão da correção) e grava a linha do histórico, numa transação. */
    boolean mudarSituacao(UUID id, int versao, MudancaDeSituacao mudanca);

    /** Marca (ou desmarca) a solução como validada. */
    boolean validar(UUID id, int versao, boolean validada, String por, Instant agora);

    /** Apaga o registro, as imagens (no banco), o histórico e o vetor. */
    boolean apagar(UUID id);

    List<ImagemDoRegistro> imagens(UUID id);

    /** Da mais antiga para a mais nova. */
    List<MudancaDeSituacao> historico(UUID id);

    Pagina pesquisar(Pesquisa pesquisa);

    /** Quem já escreveu algum registro (para o filtro por autor), por nome. */
    List<Autor> autores();

    /** O essencial de cada registro (para o aviso de caso parecido), na ordem que vier do banco. */
    List<Resumo> resumos(List<UUID> ids);

    /** Os registros da mesma tela (código igual, sem diferença de maiúsculas), do mais novo para o mais antigo. */
    List<UUID> comCodigoDeTela(String codigo, int limite);

    record Resumo(UUID id, String titulo, TipoDoRegistro tipo, SituacaoDoRegistro situacao, String chamadoDev,
                  String versaoCorrecao, String telaNome, String telaCodigo, UUID atendimentoId) {
    }

    record Pagina(List<Encontrado> registros, long total) {

        public Pagina {
            registros = List.copyOf(registros);
        }
    }

    record Autor(UUID id, String nome) {
    }
}
