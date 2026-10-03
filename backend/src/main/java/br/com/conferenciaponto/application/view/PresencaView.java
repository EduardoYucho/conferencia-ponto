package br.com.conferenciaponto.application.view;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Quem está trabalhando agora: uma linha por usuário ativo, com a situação e o porquê.
 *
 * @param agora   horário do servidor em que a situação foi calculada
 * @param pessoas online primeiro, depois quem está em intervalo e os demais, por nome
 */
public record PresencaView(LocalDate hoje, LocalTime agora, List<Pessoa> pessoas) {

    /** Situação de uma pessoa agora. Só {@link #TRABALHANDO} é "online". */
    public enum Situacao {
        /** Bateu a entrada e ainda não bateu a saída. */
        TRABALHANDO(true, "Trabalhando"),
        /** Bateu a saída e o horário dela ainda tem um período pela frente. */
        INTERVALO(false, "Em intervalo"),
        /** Bateu a saída do último período (ou saiu depois do fim do horário). */
        ENCERROU(false, "Encerrou o expediente"),
        /** O horário dela ainda não começou hoje. */
        ANTES_DO_EXPEDIENTE(false, "Antes do expediente"),
        /** O horário já começou e não há nenhuma batida: é o caso que merece atenção. */
        SEM_BATIDA(false, "Ainda não bateu o ponto"),
        /** O horário de hoje terminou sem nenhuma batida. */
        NAO_REGISTROU(false, "Não registrou ponto hoje"),
        /** Sábado, domingo ou outro dia sem horário cadastrado. */
        SEM_EXPEDIENTE(false, "Sem expediente hoje"),
        FERIADO(false, "Feriado"),
        /** Férias, folga, licença, atestado ou abono marcados para hoje. */
        AUSENCIA(false, "Ausência"),
        /** Coordenação: consulta os dados e não tem ponto próprio. */
        NAO_REGISTRA_PONTO(false, "Não registra ponto");

        private final boolean online;
        private final String rotulo;

        Situacao(boolean online, String rotulo) {
            this.online = online;
            this.rotulo = rotulo;
        }

        public boolean online() {
            return online;
        }

        public String rotulo() {
            return rotulo;
        }
    }

    /**
     * @param login    só para quem pode abrir os dados da pessoa (administração, coordenação e a própria)
     * @param motivo   frase pronta para a tela (ex.: "Férias até 15/10", "Trabalhando desde 08:02")
     * @param detalhe  complemento reservado (a justificativa de um abono, por exemplo): {@code null} para colegas
     * @param desde    horário da batida que explica a situação (entrada em aberto, saída para o intervalo...)
     * @param horario  horário de hoje pelo cadastro (ex.: "08:00–12:00 · 13:00–17:48"); {@code null} sem expediente
     * @param batidas  batidas de hoje; vazia para colegas (eles só veem a situação)
     * @param atencao  deveria estar trabalhando e não há batida
     * @param alemDoHorario trabalhando fora do horário cadastrado para hoje
     */
    public record Pessoa(UUID id, String login, String nome, boolean euMesmo, Situacao situacao, String motivo,
                         String detalhe, LocalTime desde, String horario, List<LocalTime> batidas, boolean atencao,
                         boolean alemDoHorario) {

        public boolean online() {
            return situacao.online();
        }
    }

    public long online() {
        return pessoas.stream().filter(Pessoa::online).count();
    }

    public long emIntervalo() {
        return pessoas.stream().filter(p -> p.situacao() == Situacao.INTERVALO).count();
    }
}
