package br.com.conferenciaponto.modulos;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Isolamento entre o ponto e os módulos de atendimentos e base de conhecimento (veja o package-info de
 * {@code br.com.conferenciaponto.modulos}). Uma dependência fora destas regras reprova a etapa.
 *
 * <p>As regras dos módulos aceitam pacote ainda vazio ({@code allowEmptyShould}): passam a valer de verdade à
 * medida que as classes chegam. {@link RegraDosModulosPegaViolacaoTest} prova que elas reprovam o que devem.
 */
@AnalyzeClasses(packages = "br.com.conferenciaponto", importOptions = ArquiteturaModulosTest.SemClassesDeTeste.class)
class ArquiteturaModulosTest {

    /**
     * Só o código de produção. O DoNotIncludeTests do ArchUnit reconhece só target/test-classes, e o
     * "ponto atualizar" compila em target-app/test-classes (perfil app): os exemplos de violação entrariam.
     */
    public static final class SemClassesDeTeste implements ImportOption {
        @Override
        public boolean includes(Location local) {
            return !local.contains("/test-classes/");
        }
    }

    static final String MODULOS = "br.com.conferenciaponto.modulos";
    private static final String RAIZ = "br.com.conferenciaponto.";

    /** Do ponto, os módulos só podem usar estes pacotes... */
    private static final List<String> PACOTES_PERMITIDOS = List.of(
            "br.com.conferenciaponto.domain.exception",     // exceções que o GlobalExceptionHandler transforma em mensagem
            "br.com.conferenciaponto.infrastructure.log");  // protocolo e log por usuário

    /** ...e estas classes (o usuário logado e o envelope das respostas). */
    private static final Set<String> CLASSES_PERMITIDAS = Set.of(
            "br.com.conferenciaponto.domain.model.Usuario",
            "br.com.conferenciaponto.domain.model.Perfil",
            "br.com.conferenciaponto.domain.port.UsuarioRepository",
            "br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios",
            "br.com.conferenciaponto.infrastructure.web.dto.ApiResponse",
            "br.com.conferenciaponto.infrastructure.web.dto.ApiErro");

    static final DescribedPredicate<JavaClass> PONTO_FORA_DO_PERMITIDO =
            new DescribedPredicate<>("classes do ponto que os módulos não podem usar") {
                @Override
                public boolean test(JavaClass classe) {
                    String nome = classe.getName();
                    if (!nome.startsWith(RAIZ) || nome.startsWith(MODULOS + ".")) {
                        return false;
                    }
                    String pacote = classe.getPackageName();
                    boolean pacotePermitido = PACOTES_PERMITIDOS.stream()
                            .anyMatch(p -> pacote.equals(p) || pacote.startsWith(p + "."));
                    boolean classePermitida = CLASSES_PERMITIDAS.stream()
                            .anyMatch(c -> nome.equals(c) || nome.startsWith(c + "$"));
                    return !pacotePermitido && !classePermitida;
                }
            };

    @ArchTest
    static final ArchRule o_ponto_nao_usa_os_modulos = noClasses()
            .that().resideOutsideOfPackage(MODULOS + "..")
            .should().dependOnClassesThat().resideInAPackage(MODULOS + "..")
            .because("os módulos podem ser extraídos do ponto no futuro; o ponto não pode depender deles");

    @ArchTest
    static final ArchRule os_modulos_usam_so_o_permitido_do_ponto = regraDosModulos();

    @ArchTest
    static final ArchRule o_atendimento_nao_conhece_a_base = noClasses()
            .that().resideInAPackage(MODULOS + ".atendimento..")
            .should().dependOnClassesThat().resideInAPackage(MODULOS + ".conhecimento..")
            .because("quem junta o gerador e a base é a tela (ex.: o aviso de caso parecido)")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule a_base_conhece_so_os_eventos_do_atendimento = noClasses()
            .that().resideInAPackage(MODULOS + ".conhecimento..")
            .should().dependOnClassesThat(resideInAPackage(MODULOS + ".atendimento..")
                    .and(not(resideInAPackage(MODULOS + ".atendimento.application.evento.."))))
            .because("a base recebe do gerador só o evento de texto confirmado")
            .allowEmptyShould(true);

    static ArchRule regraDosModulos() {
        return noClasses()
                .that().resideInAPackage(MODULOS + "..")
                .should().dependOnClassesThat(PONTO_FORA_DO_PERMITIDO)
                .because("do ponto, os módulos usam só o usuário logado, o envelope de resposta, as exceções e o log")
                .allowEmptyShould(true);
    }
}
