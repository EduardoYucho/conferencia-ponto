package br.com.conferenciaponto.domain.acesso;

import java.util.List;
import java.util.Set;

/** As permissões das telas do ponto. */
public final class PermissoesDoPonto implements FonteDePermissoes {

    /** Tem o próprio ponto: as telas com os próprios dados; recebe o horário padrão e o ciclo do banco. */
    public static final String PROPRIO = "ponto.proprio";
    /** Lança e corrige o próprio ponto (tudo o que grava nos próprios dados). */
    public static final String LANCAR = "ponto.lancar";
    /** Consulta o ponto de qualquer pessoa (só leitura). */
    public static final String EQUIPE = "ponto.equipe";
    /** Muda o horário, a pasta de comprovantes e a planilha de outras pessoas. */
    public static final String CONFIGURAR_EQUIPE = "ponto.equipe.configurar";
    /** Marca o mês de uma pessoa como conferido (e reabre), o que trava as mudanças à mão nele. */
    public static final String CONFERIR = "ponto.conferir";
    /** Cadastra e exclui feriados (valem para todos). */
    public static final String FERIADOS = "ponto.feriados";

    public static final Set<String> CODIGOS = Set.of(PROPRIO, LANCAR, EQUIPE, CONFIGURAR_EQUIPE, CONFERIR, FERIADOS);

    @Override
    public String grupo() {
        return "Ponto";
    }

    @Override
    public int ordem() {
        return 10;
    }

    @Override
    public List<Permissao> permissoes() {
        return List.of(
                Permissao.de(PROPRIO, "Ter o próprio ponto",
                        "Painel, auditoria, banco de horas, folgas e comprovantes da própria pessoa."),
                Permissao.de(LANCAR, "Lançar no próprio ponto",
                        "Batidas, correções, folgas, banco de horas, envio de comprovantes, conferência com o RH, "
                                + "horário, pasta e planilha.").requer(PROPRIO),
                Permissao.de(EQUIPE, "Consultar o ponto da equipe",
                        "Vê o ponto de qualquer pessoa, sem alterar, e os detalhes de todos em Equipe."),
                Permissao.de(CONFIGURAR_EQUIPE, "Configurar o ponto da equipe",
                        "Muda o horário, a pasta de comprovantes e a planilha de outras pessoas.").requer(EQUIPE),
                Permissao.de(CONFERIR, "Conferir o mês",
                        "Marca o mês de uma pessoa como conferido, na tela Banco de horas, e reabre. O mês conferido "
                                + "fica travado para mudanças à mão.").requer(EQUIPE),
                Permissao.de(FERIADOS, "Cadastrar feriados",
                        "Cadastra e exclui feriados, que valem para todos, inclusive os que vêm do relatório do RH."));
    }
}
