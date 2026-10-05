<script setup>
import { computed, ref, watch } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { mensagemDe } from '@/utils/erros'

/**
 * Pasta onde chegam os comprovantes PDF da pessoa. O sistema roda num computador só: a pasta precisa ser
 * acessível de lá (no mesmo computador, uma pasta compartilhada na rede ou uma pasta sincronizada da nuvem).
 */
const props = defineProps({
  /** Pasta gravada (null = sem monitoramento). */
  pasta: { type: String, default: null },
  /** Situação do monitor: { situacao: 'ATIVO'|'INDISPONIVEL'|'INICIANDO'|'SEM_PASTA'|'DESABILITADO', mensagem }. */
  monitor: { type: Object, default: null },
  podeEditar: { type: Boolean, default: false },
  /** Id de outra pessoa (o administrador alterando a pasta dela); null = a própria. */
  usuarioId: { type: String, default: null },
  /**
   * A pasta em tela é de outra pessoa. Sem o id dela (a lista de pessoas ainda não carregou) não dá para
   * alterar: salvar sem id gravaria a pasta de quem está logado.
   */
  deOutraPessoa: { type: Boolean, default: false },
})
const emit = defineEmits(['salvo'])

const texto = ref(props.pasta ?? '')
const verificacao = ref(null)
const verificando = ref(false)
const salvando = ref(false)
const erro = ref('')
const mensagem = ref('')

watch(() => props.pasta, (p) => (texto.value = p ?? ''))

const alterada = computed(() => (texto.value.trim() || null) !== (props.pasta ?? null))
const semIdentificacao = computed(() => props.deOutraPessoa && !props.usuarioId)

const situacao = computed(() => {
  const m = props.monitor
  if (!props.pasta) return { cor: 'bg-tinta-apagada', texto: 'Nenhuma pasta: os comprovantes chegam só pelo envio na tela.' }
  if (m?.situacao === 'ATIVO') return { cor: 'bg-credito', texto: 'Monitorando: cada PDF novo nesta pasta é importado na hora.' }
  if (m?.situacao === 'INDISPONIVEL') {
    return { cor: 'bg-amber-500 animate-pulse', texto: `Pasta inacessível agora${m.mensagem ? ` (${m.mensagem})` : ''}. O sistema tenta de novo sozinho.` }
  }
  if (m?.situacao === 'DESABILITADO') return { cor: 'bg-tinta-apagada', texto: 'O monitoramento de pastas está desligado no servidor.' }
  return { cor: 'bg-amber-500 animate-pulse', texto: 'Conectando à pasta…' }
})

async function verificar() {
  verificando.value = true
  erro.value = ''
  verificacao.value = null
  try {
    verificacao.value = await pontoApi.verificarPasta(texto.value.trim())
  } catch (e) {
    erro.value = mensagemDe(e)
  } finally {
    verificando.value = false
  }
}

async function salvar(valor = texto.value) {
  erro.value = ''
  mensagem.value = ''
  if (semIdentificacao.value) {
    erro.value = 'Esta pessoa ainda não foi identificada: a pasta dela não pode ser alterada agora.'
    return
  }
  salvando.value = true
  let r
  try {
    const pasta = (valor ?? '').trim()
    r = props.usuarioId
      ? await pontoApi.definirPastaDe(props.usuarioId, pasta)
      : await pontoApi.salvarMinhaPasta(pasta)
  } catch (e) {
    erro.value = mensagemDe(e)
    return
  } finally {
    salvando.value = false
  }
  // salvou: nada depois daqui pode parecer falha da gravação
  verificacao.value = r?.pasta ? r : null
  mensagem.value = r?.pasta
    ? (r.acessivel ? 'Pasta salva. Os PDFs que já estão nela serão importados (sem duplicar).' : 'Pasta salva, mas ainda não está acessível.')
    : 'Monitoramento desligado. Envie os comprovantes pela tela.'
  emit('salvo', r ?? { pasta: null })
}
</script>

<template>
  <div>
    <p class="flex items-start gap-2 text-sm" role="status">
      <span class="mt-1.5 size-2 shrink-0 rounded-full" :class="situacao.cor" aria-hidden="true" />
      <span>
        <span v-if="pasta" class="carimbo block break-all font-semibold">{{ pasta }}</span>
        <span class="text-tinta-suave">{{ situacao.texto }}</span>
      </span>
    </p>

    <p v-if="podeEditar && semIdentificacao" role="alert" class="mt-4 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">
      Não foi possível identificar esta pessoa (a lista de pessoas não carregou), então a pasta dela não pode ser
      alterada agora. Aguarde um instante; se continuar, recarregue a página.
    </p>
    <form v-else-if="podeEditar" class="mt-4" novalidate @submit.prevent="salvar()">
      <label class="block">
        <span class="rotulo">Caminho da pasta</span>
        <input
          v-model="texto"
          type="text"
          class="campo mt-1.5 text-sm"
          placeholder="Ex.: C:\Users\fulano\Downloads\Ponto  ou  \\NOME-DO-PC\Ponto"
          spellcheck="false"
          autocapitalize="off"
        />
      </label>
      <div class="mt-2 flex flex-wrap items-center gap-2">
        <button type="button" class="botao-secundario py-1.5! text-xs" :disabled="verificando || !texto.trim()" @click="verificar">
          {{ verificando ? 'Testando…' : 'Testar acesso' }}
        </button>
        <button type="submit" class="botao-primario py-1.5! text-xs" :disabled="salvando || !alterada">
          {{ salvando ? 'Salvando…' : 'Salvar pasta' }}
        </button>
        <button
          v-if="pasta"
          type="button"
          class="text-xs text-tinta-suave underline underline-offset-4 hover:text-carimbo"
          :disabled="salvando"
          @click="salvar('')"
        >parar de monitorar</button>
      </div>

      <p
        v-if="verificacao"
        class="mt-3 rounded-[3px] border px-3 py-2 text-sm"
        :class="verificacao.acessivel ? 'border-credito/40 bg-credito/10 text-credito' : 'border-carimbo/40 bg-carimbo/10 text-carimbo'"
      >
        {{ verificacao.acessivel ? 'O sistema consegue abrir esta pasta.' : verificacao.aviso }}
      </p>
      <p v-if="mensagem" class="mt-2 text-sm text-tinta-suave">{{ mensagem }}</p>
      <p v-if="erro" role="alert" class="mt-3 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">{{ erro }}</p>

      <details class="mt-4 text-sm text-tinta-suave">
        <summary class="cursor-pointer font-semibold hover:text-tinta">Qual caminho usar?</summary>
        <ul class="mt-2 list-disc space-y-1.5 pl-5">
          <li>
            A pasta precisa estar acessível <b>no computador onde o sistema está instalado</b>
            (o servidor), e não só no seu.
          </li>
          <li>Pasta no próprio servidor: o caminho normal, ex.: <code class="carimbo">C:\Users\fulano\Downloads\Ponto</code>.</li>
          <li>
            Pasta no seu computador: compartilhe-a na rede (Propriedades → Compartilhamento) e use o caminho de rede,
            ex.: <code class="carimbo">\\192.168.0.10\Ponto</code> ou <code class="carimbo">\\NOME-DO-PC\Ponto</code>.
          </li>
          <li>OneDrive ou Google Drive: use a pasta sincronizada no servidor.</li>
          <li>Cada pessoa tem a sua pasta: dois usuários não podem usar a mesma.</li>
          <li>Não tem como compartilhar? Deixe em branco e envie os PDFs pela tela.</li>
        </ul>
      </details>
    </form>
  </div>
</template>
