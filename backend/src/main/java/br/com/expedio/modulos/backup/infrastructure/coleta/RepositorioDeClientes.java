package br.com.expedio.modulos.backup.infrastructure.coleta;

import br.com.expedio.modulos.backup.domain.Cliente;
import br.com.expedio.modulos.backup.infrastructure.config.ConfiguracaoDoBackup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;


/** Os clientes, em {@code <pasta-estado>/clientes.json}. */
@Component
public class RepositorioDeClientes {

    private final Path arquivo;
    private final ObjectMapper json;

    public RepositorioDeClientes(ConfiguracaoDoBackup configuracao, ObjectMapper json) {
        this.arquivo = configuracao.pastaInterna().resolve("clientes.json");
        this.json = json;
    }

    public synchronized List<Cliente> listar() {
        if (!Files.exists(arquivo)) {
            return List.of();
        }
        try {
            List<Cliente> clientes = new ArrayList<>(json.readValue(arquivo.toFile(), new TypeReference<List<Cliente>>() {
            }));
            clientes.sort(Comparator.comparing(c -> c.nome().toLowerCase(Locale.ROOT)));
            return clientes;
        } catch (IOException e) {
            throw new UncheckedIOException("Não consegui ler " + arquivo, e);
        }
    }

    public synchronized Optional<Cliente> buscar(String nome) {
        return listar().stream().filter(c -> c.nome().equalsIgnoreCase(nome)).findFirst();
    }

    /** Grava (novo ou alterado). {@code nomeAnterior} permite renomear. */
    public synchronized Cliente salvar(String nomeAnterior, Cliente cliente) {
        List<Cliente> clientes = new ArrayList<>(listar());
        String procurado = nomeAnterior == null ? cliente.nome() : nomeAnterior;
        clientes.removeIf(c -> c.nome().equalsIgnoreCase(procurado) || c.nome().equalsIgnoreCase(cliente.nome()));
        clientes.add(cliente);
        ArquivosJson.gravar(json, arquivo, clientes);
        return cliente;
    }

    public synchronized boolean remover(String nome) {
        List<Cliente> clientes = new ArrayList<>(listar());
        boolean removeu = clientes.removeIf(c -> c.nome().equalsIgnoreCase(nome));
        if (removeu) {
            ArquivosJson.gravar(json, arquivo, clientes);
        }
        return removeu;
    }
}
