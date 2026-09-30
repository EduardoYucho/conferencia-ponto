package br.com.conferenciaponto.application.evento;

/** Quem provocou a alteração da jornada (o front-end decide como reagir a cada uma). */
public enum OrigemAtualizacao {
    /** Botão "bater ponto" / POST /jornadas/batidas. */
    API,
    /** Lançamento manual de fim de semana/feriado. */
    MANUAL,
    /** Exclusão do registro do dia. */
    EXCLUSAO,
    /** Comprovante PDF detectado pelo monitor de diretório. */
    COMPROVANTE_PDF,
    /** Ajuste manual das batidas (correção do RH, falha no relógio). */
    AJUSTE,
    /** Férias/atestado/licença/folga cadastrados ou removidos (o tipo do dia mudou). */
    AUSENCIA,
    /** Dados aceitos de um relatório do RH na conciliação. */
    CONCILIACAO
}
