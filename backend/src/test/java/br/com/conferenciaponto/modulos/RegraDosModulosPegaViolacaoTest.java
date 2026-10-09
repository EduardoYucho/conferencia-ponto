package br.com.conferenciaponto.modulos;

import br.com.conferenciaponto.modulos.atendimento.exemplo.ExemploQueUsaOQueNaoPode;
import br.com.conferenciaponto.modulos.atendimento.exemplo.ExemploQueUsaSoOPermitido;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prova que a regra de isolamento dos módulos reprova o que deve (enquanto os módulos ainda estão vazios). */
class RegraDosModulosPegaViolacaoTest {

    @Test
    void reprovaUmModuloUsandoUmaParteDoPontoQueNaoEstaNaLista() {
        JavaClasses exemplo = new ClassFileImporter().importClasses(ExemploQueUsaOQueNaoPode.class);

        assertThatThrownBy(() -> ArquiteturaModulosTest.regraDosModulos().check(exemplo))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("MotorCalculoJornadaService");
    }

    @Test
    void aceitaOQueEstaNaLista() {
        JavaClasses exemplo = new ClassFileImporter().importClasses(ExemploQueUsaSoOPermitido.class);

        assertThatCode(() -> ArquiteturaModulosTest.regraDosModulos().check(exemplo)).doesNotThrowAnyException();
    }
}
