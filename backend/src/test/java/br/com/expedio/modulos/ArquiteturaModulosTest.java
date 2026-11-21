package br.com.expedio.modulos;

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
 * Isolamento entre o ponto e os módulos (atendimentos, base de conhecimento e monitor fiscal; veja o package-info de
 * {@code br.com.expedio.modulos}). Uma dependência fora destas regras reprova a etapa.
 *
 * <p>As regras dos módulos aceitam pacote ainda vazio ({@code allowEmptyShould}): passam a valer de verdade à
 * medida que as classes chegam. {@link RegraDosModulosPegaViolacaoTest} prova que elas reprovam o que devem.
 */
@AnalyzeClasses(packages = "br.com.expedio", importOptions = ArquiteturaModulosTest.SemClassesDeTeste.class)
class ArquiteturaModulosTest {

    /**
     * Só o código de produção. O DoNotIncludeTests do ArchUnit reconhece só target/test-classes, e o
     * "expedio atualizar" compila em target-app/test-classes (perfil app): os exemplos de violação entrariam.
     */
    public static final class SemClassesDeTeste implements ImportOption {
        @Override
        public boolean includes(Location local) {
            return !local.contains("/test-classes/");
        }
    }

    static final String MODULOS = "br.com.expedio.modulos";
    private static final String RAIZ = "br.com.expedio.";

    /** Do ponto, os módulos só podem usar estes pacotes... */
    private static final List<String> PACOTES_PERMITIDOS = List.of(
            "br.com.expedio.domain.exception",     // exceções que o GlobalExceptionHandler transforma em mensagem
            "br.com.expedio.infrastructure.log");  // protocolo e log por usuário

    /**
     * ...e estas classes (o usuário logado, as permissões que cada módulo declara, o envelope das respostas e o pedido
     * de aviso no sino).
     */
    private static final Set<String> CLASSES_PERMITIDAS = Set.of(
            "br.com.expedio.domain.model.Usuario",
            "br.com.expedio.domain.model.Perfil",
            "br.com.expedio.domain.acesso.FonteDePermissoes",
            "br.com.expedio.domain.acesso.Permissao",
            "br.com.expedio.domain.port.UsuarioRepository",
            "br.com.expedio.infrastructure.web.acesso.AcessoUsuarios",
            "br.com.expedio.infrastructure.web.dto.ApiResponse",
            "br.com.expedio.infrastructure.web.dto.ApiErro",
            "br.com.expedio.application.evento.AvisoDeModulo");  // o aviso no sino (o sino é do ponto)

    static final DescribedPredicate<JavaClass> EXPEDIO_FORA_DO_PERMITIDO =
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
            .because("a base usa do gerador só o que ele publica: o evento do texto confirmado e os vetores")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule o_monitor_fiscal_nao_conhece_os_outros_modulos = noClasses()
            .that().resideInAPackage(MODULOS + ".fiscal..")
            .should().dependOnClassesThat().resideInAnyPackage(MODULOS + ".atendimento..", MODULOS + ".conhecimento..")
            .because("o monitor fiscal é um módulo à parte; a ligação com os atendimentos (M5) passa por uma consulta")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule os_outros_modulos_nao_conhecem_o_monitor_fiscal = noClasses()
            .that().resideInAnyPackage(MODULOS + ".atendimento..", MODULOS + ".conhecimento..")
            .should().dependOnClassesThat().resideInAPackage(MODULOS + ".fiscal..")
            .because("o monitor fiscal pode ser retirado sem mexer nos atendimentos nem na base")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule os_backups_de_clientes_nao_conhecem_os_outros_modulos = noClasses()
            .that().resideInAPackage(MODULOS + ".backup..")
            .should().dependOnClassesThat().resideInAnyPackage(MODULOS + ".atendimento..", MODULOS + ".conhecimento..",
                    MODULOS + ".fiscal..")
            .because("os backups de clientes são um módulo à parte")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule os_outros_modulos_nao_conhecem_os_backups = noClasses()
            .that().resideInAnyPackage(MODULOS + ".atendimento..", MODULOS + ".conhecimento..", MODULOS + ".fiscal..")
            .should().dependOnClassesThat().resideInAPackage(MODULOS + ".backup..")
            .because("os backups de clientes podem ser retirados sem mexer nos outros módulos")
            .allowEmptyShould(true);

    static ArchRule regraDosModulos() {
        return noClasses()
                .that().resideInAPackage(MODULOS + "..")
                .should().dependOnClassesThat(EXPEDIO_FORA_DO_PERMITIDO)
                .because("do ponto, os módulos usam só o usuário logado, o envelope de resposta, as exceções e o log")
                .allowEmptyShould(true);
    }
}
