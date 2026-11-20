package br.com.receptorbackup.cliente;

import java.util.List;

/**
 * Um cliente cadastrado no Receptor: o nome, a pasta do sistema nele (só lembrete para quem escolhe a pasta, o
 * navegador não informa o caminho) e as pastas marcadas na última coleta.
 */
public record Cliente(String nome, String pastaSistema, List<String> ultimaSelecao) {

    public Cliente {
        nome = nome == null ? "" : nome.trim();
        pastaSistema = pastaSistema == null ? "" : pastaSistema.trim();
        ultimaSelecao = ultimaSelecao == null ? List.of() : List.copyOf(ultimaSelecao);
    }

    public Cliente comSelecao(List<String> selecao) {
        return new Cliente(nome, pastaSistema, selecao);
    }
}
