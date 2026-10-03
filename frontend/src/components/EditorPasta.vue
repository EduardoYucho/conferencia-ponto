<script setup>
import { computed, ref, useId, watch } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { avisar } from '@/utils/avisar'
import { mensagemDe } from '@/utils/erros'
import Icone from '@/components/Icone.vue'

/**
 * Pasta onde chegam os comprovantes PDF da pessoa. O sistema roda num computador só: a pasta precisa ser
 * acessível de lá (no mesmo computador, uma pasta compartilhada na rede ou uma pasta sincronizada da nuvem).
 */
const props = defineProps({
  /** Pasta gravada (null = o sistema não lê nenhuma pasta). */
  pasta: { type: String, default: null },
  /** Situação da leitura da pasta: { situacao: 'ATIVO'|'INDISPONIVEL'|'INICIANDO'|'SEM_PASTA'|'DESABILITADO', mensagem }. */
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
/** A tela de usuários mostra um editor por pessoa: cada um com os próprios ids. */
const id = useId()

const texto = ref(props.pasta ?? '')
const verificacao = ref(null)
const verificando = ref(false)
const salvando = ref(false)
const erro = ref('')
const confirmandoParar = ref(false)
const ajudaAberta = ref(false)

watch(() => props.pasta, (p) => {
  texto.value = p ?? ''
  confirmandoParar.value = false
})

const alterada = computed(() => (texto.value.trim() || null) !== (props.pasta ?? null))
const semIdentificacao = computed(() => props.deOutraPessoa && !props.usuarioId)

/** O servidor diz como está a leitura da pasta; aqui só se escolhe a frase e a cor. */
const situacao = computed(() => {
  const m = props.monitor
  if (!props.pasta) {
    return {
      selo: 'selo-neutro', rotulo: 'Sem pasta',
      texto: props.deOutraPessoa
        ? 'Nenhuma pasta escolhida: os comprovantes chegam só quando a pessoa envia pela tela.'
        : 'Nenhuma pasta escolhida: os comprovantes chegam só quando você envia pela tela.',
    }
  }
  if (m?.situacao === 'ATIVO') {
    return { selo: 'selo-positivo', rotulo: 'Funcionando', texto: 'Cada PDF novo que chega nesta pasta vira batida na hora.' }
  }
  if (m?.situacao === 'INDISPONIVEL') {
    return {
      selo: 'selo-atencao', rotulo: 'Sem acesso à pasta', pisca: true,
      texto: `O sistema não está conseguindo abrir a pasta agora${m.mensagem ? ` (${m.mensagem})` : ''}. Ele tenta de novo sozinho.`,
    }
  }
  if (m?.situacao === 'DESABILITADO') {
    return { selo: 'selo-neutro', rotulo: 'Desligado', texto: 'A leitura de pastas está desligada no servidor.' }
  }
  return { selo: 'selo-atencao', rotulo: 'Conectando…', pisca: true, texto: 'O sistema está abrindo a pasta.' }
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
  confirmandoParar.value = false
  verificacao.value = r?.pasta ? r : null
  if (!r?.pasta) avisar('O sistema parou de ler a pasta. Os comprovantes agora chegam só pelo envio na tela.', 'info')
  else if (r.acessivel) avisar('Pasta salva. Os PDFs que já estão nela serão lidos (o mesmo comprovante nunca vira batida duas vezes).')
  else avisar('Pasta salva, mas o sistema ainda não consegue abri-la. Veja o aviso junto do campo.', 'info')
  emit('salvo', r ?? { pasta: null })
}
</script>

<template>
  <div>
    <div class="flex flex-wrap items-start gap-x-3 gap-y-2 rounded-xl bg-superficie-2 px-4 py-3" role="status">
      <span class="selo" :class="situacao.selo">
        <span class="size-2 rounded-full bg-current" :class="{ 'animate-pulse': situacao.pisca }" aria-hidden="true" />
        {{ situacao.rotulo }}
      </span>
      <p class="min-w-0 flex-1 basis-64 text-[0.95rem]">
        <span v-if="pasta" class="block font-mono text-sm font-semibold break-all text-texto">{{ pasta }}</span>
        <span class="text-texto-2">{{ situacao.texto }}</span>
      </p>
    </div>

    <p v-if="podeEditar && semIdentificacao" role="alert" class="aviso-erro mt-4">
      Não foi possível identificar esta pessoa (a lista de pessoas não carregou), então a pasta dela não pode ser
      alterada agora. Aguarde um instante; se continuar, recarregue a página.
    </p>
    <form v-else-if="podeEditar" class="mt-4" novalidate @submit.prevent="salvar()">
      <label class="rotulo" :for="`${id}-caminho`">Caminho da pasta</label>
      <input
        :id="`${id}-caminho`"
        v-model="texto"
        type="text"
        class="campo mt-1.5 font-mono text-[0.95rem]!"
        placeholder="C:\Users\fulano\Downloads\Ponto"
        spellcheck="false"
        autocapitalize="off"
        autocomplete="off"
        :aria-describedby="`${id}-ajuda`"
      />
      <p :id="`${id}-ajuda`" class="mt-1 text-sm text-texto-3">
        Pode ser uma pasta do computador onde o sistema está instalado ou uma pasta da rede, como
        <span class="font-mono">\\NOME-DO-PC\Ponto</span>.
      </p>
      <div class="mt-3 flex flex-wrap items-center gap-2">
        <button type="submit" class="botao-primario min-h-11" :disabled="salvando || !alterada">
          {{ salvando && !confirmandoParar ? 'Salvando…' : 'Salvar pasta' }}
        </button>
        <button type="button" class="botao-secundario min-h-11" :disabled="verificando || !texto.trim()" @click="verificar">
          {{ verificando ? 'Testando…' : 'Testar se o sistema abre a pasta' }}
        </button>
        <button
          v-if="pasta && !confirmandoParar"
          type="button"
          class="botao-linha"
          :disabled="salvando"
          @click="confirmandoParar = true"
        >Parar de usar esta pasta</button>
      </div>

      <!-- confirmação antes de parar de ler a pasta -->
      <div v-if="confirmandoParar" class="mt-3 rounded-xl border border-negativo-borda bg-negativo-suave p-4" role="group" aria-label="Confirmar: parar de usar esta pasta">
        <p class="font-bold text-negativo">Parar de usar esta pasta?</p>
        <p class="mt-1 text-[0.95rem] text-texto-2">
          O sistema deixa de ler os PDFs que chegarem nela: os comprovantes passam a entrar só pelo envio na tela.
          O que já virou batida continua como está.
        </p>
        <div class="mt-3 flex flex-wrap gap-2">
          <button type="button" class="botao-perigo min-h-11" :disabled="salvando" @click="salvar('')">
            {{ salvando ? 'Parando…' : 'Sim, parar de usar' }}
          </button>
          <button type="button" class="botao-secundario min-h-11" :disabled="salvando" @click="confirmandoParar = false">Cancelar</button>
        </div>
      </div>

      <p v-if="verificacao" class="mt-3" :class="verificacao.acessivel ? 'aviso-ok' : 'aviso-erro'" role="status">
        {{ verificacao.acessivel ? 'O sistema consegue abrir esta pasta.' : verificacao.aviso }}
      </p>
      <p v-if="erro" role="alert" class="aviso-erro mt-3">{{ erro }}</p>

      <div class="mt-3">
        <button type="button" class="link flex min-h-11 items-center gap-1 text-[0.95rem]" :aria-expanded="ajudaAberta" :aria-controls="`${id}-dicas`" @click="ajudaAberta = !ajudaAberta">
          Qual caminho usar?
          <Icone nome="abaixo" tamanho="18" class="transition-transform" :class="{ 'rotate-180': ajudaAberta }" />
        </button>
        <ul v-if="ajudaAberta" :id="`${id}-dicas`" class="list-disc space-y-1.5 rounded-xl bg-superficie-2 py-3 pr-4 pl-9 text-[0.95rem] text-texto-2">
          <li>
            A pasta precisa abrir <b>no computador onde o sistema está instalado</b> (o servidor), e não só no seu.
          </li>
          <li>Pasta no próprio servidor: o caminho normal, como <span class="font-mono text-sm break-words">C:\Users\fulano\Downloads\Ponto</span>.</li>
          <li>
            Pasta no seu computador: compartilhe-a na rede (Propriedades → Compartilhamento) e use o caminho de rede,
            como <span class="font-mono text-sm break-words">\\192.168.0.10\Ponto</span> ou <span class="font-mono text-sm break-words">\\NOME-DO-PC\Ponto</span>.
          </li>
          <li>OneDrive ou Google Drive: use a pasta que eles mantêm no servidor.</li>
          <li>Cada pessoa tem a sua pasta: duas pessoas não podem usar a mesma.</li>
          <li>Não tem como compartilhar? Deixe em branco e envie os PDFs pela tela.</li>
        </ul>
      </div>
    </form>
  </div>
</template>
