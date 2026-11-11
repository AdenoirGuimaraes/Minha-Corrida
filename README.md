# Minha Corrida

Mostra, em cima do Uber Driver e do inDrive, **quanto você ganha por km rodado**:

    R$/km = valor da corrida ÷ (km até o passageiro + km da viagem)

## Arquitetura
- **core** (Kotlin puro): parsers por plataforma → cálculo do R$/km → classificação contra o mínimo. Testado na JVM.
- **app** (Android): `RideReaderService` (Acessibilidade) lê os textos da tela, `RideOverlayController` mostra o resultado.
- Lê a árvore de acessibilidade em vez de print + OCR, por isso responde em ~0,1 s.

## Como rodar
1. Abra a pasta no **Android Studio** (Ladybug ou mais novo) e aguarde o *Gradle Sync*.
2. Rode os testes do núcleo: `gradle :core:test`.
3. Instale no celular (*Run ▶ app*).
4. No app, toque em **Ativar leitura de tela** → *Apps instalados* → **Minha Corrida** → ativar.
   - Android 13+: se aparecer "configuração restrita", vá em *Configurações → Apps → Minha Corrida → ⋮ → Permitir configurações restritas* e tente de novo.
5. Defina o **mínimo R$/km**. Overlay verde = atinge o mínimo; vermelho = abaixo.

## Exemplos (das telas reais)
| App | Valor | Busca | Viagem | Total | R$/km |
|---|---|---|---|---|---|
| Uber | R$ 8,91 | 6,6 km | 2,3 km | 8,9 km | **R$ 1,00** |
| inDrive (detalhe) | R$ 10 | 3,3 km | 7,3 km | 10,6 km | **R$ 0,94** |
| inDrive (lista) | R$ 10 | — | 7,0 km | 7,0 km | R$ 1,43 (só trajeto) |

## Prioridade de leitura (vários apps abertos)
1. **Cartão da Uber em sobreposição** — lido mesmo com o inDrive na frente; o OCR cobre só a área do cartão (partes de cima e de baixo).
2. **App em primeiro plano** — ex.: oferta do inDrive aberta em tela inteira (com leitura do mapa).
3. Nada a ler (tela inicial do inDrive, só a bolha da Uber).

O cartão da Uber só é mostrado **completo** (valor + distância até o passageiro + distância da viagem). Se vier só a parte de cima, o leitor insiste a cada 0,5 s e, depois de 2 leituras, amplia o OCR até o fim da tela para pegar a parte de baixo — sem devolver a vez ao inDrive nesse meio-tempo. Os textos de todas as janelas da Uber (partes de cima e de baixo) são juntados numa leitura só.

Quando o cartão da Uber fecha, o inDrive recomeça a leitura do mapa do zero. Uma sobreposição grande da Uber que não seja oferta é ignorada após algumas leituras sem resultado.

## Lucro, nota, apps e voz
- **Lucro real líquido**: informe preço do combustível (R$/l), consumo (km/l) e, se quiser, outros
  custos por km. O card mostra `valor − km total × custo por km` ("Lucro" ou "Prejuízo"). A tela de
  configurações mostra o custo por km e quanto o mínimo de R$/km deixa de lucro por km.
- **Nota mínima do passageiro**: abaixo dela a corrida fica vermelha, mesmo com bom R$/km.
  Passageiro sem nota (novo) não é recusado. O app não toca em "Recusar": só avisa.
- **Apps lidos**: Uber e inDrive podem ser ligados/desligados; app desligado nem é lido.
- **Voz**: "Aceitar corrida. 8 reais e 50 centavos. inDrive" só nas corridas verdes, uma vez por
  oferta, depois de o card aparecer. Pode ser desligada.

## Card da corrida
De um lado ao outro da tela. **Faixa do topo na cor do app** (Uber preta com letras brancas,
inDrive verde-limão com letras pretas) e a nota do passageiro. Três quadrados:
**R$/km** (com km total e a soma), **Lucro** (com valor da corrida e gasto; "Prejuízo" se negativo)
e **Mín. verde** (menor valor da corrida que deixa o card verde: atinge o mínimo de R$/km **e** o lucro líquido mínimo — vale o maior dos dois; embaixo aparecem os dois mínimos). Fundo verde = boa, amarelo = lucro baixo, vermelho = abaixo.
Voz em velocidade 1,4×.

## Tela do app (tema preto e amarelo)
- Abas **Configurações** e **Simulador**. O simulador mostra o mesmo card da leitura real, recalculado a cada número digitado
  (corrida de exemplo, mínimo R$/km, nota mínima e custos), antes mesmo de salvar.
  Usa a mesma regra do leitor (`EvaluateRideUseCase`). Botão **Testar voz** fala a corrida simulada.
- **Checklist** de apps lidos e de "Falar corridas": verde marcado, vermelho desmarcado.

## Aba Corridas (histórico)
- Guardadas **só as corridas que ficaram com você**, seguindo as telas de cada app (calibrado com
  os relatórios de diagnóstico de 09/11) — nada é gravado pela cor do card nem pelo toque:

  | Etapa | Uber | inDrive |
  |---|---|---|
  | Pedido | cartão lido (guardado como "último pedido") | "Pedido de viagem" lido |
  | Aguardando | — | "Oferecendo sua tarifa R$ 13. Aguarde a resposta" |
  | **Confirmada → grava** | "Encontro com Fernanda" / "Destino de …" | "R$ 13 · Dinheiro" + "Estou aqui" / "Começar a corrida" / … |
  | **Concluída → ✅ realizada** | "Como foi a viagem?" / "Avaliar usuário" | "Avalie seu passageiro" |

  - A corrida gravada é o último pedido lido daquele app (até 10 min antes), de **qualquer cor**.
    No inDrive o valor é o da tela da corrida — inclui contraproposta pelo valor pronto **ou pelo lápis** —
    e a taxa é descontada.
  - Ofertas que chegam durante uma corrida não são gravadas; telas repetidas da mesma corrida também não.
  - Os toques no cartão da Uber ("Tenho interesse", X) **não chegam ao app** (o cartão bloqueia a leitura):
    por isso a Uber é confirmada pela tela "Encontro com…".
  - inDrive: o toque num valor só refaz o card; **Pular/Fechar** recusa.
- **Registrar Corrida**: corrida que o leitor não gravou, ou particular. Escolha o app
  (**Particular, Uber ou inDrive**), valor, km até o passageiro (opcional), km da viagem e hora.
  No inDrive a taxa configurada é descontada (como no leitor); Uber e Particular sem desconto.
  Mostra o cálculo do card enquanto digita e entra já realizada.
- **Toque longo** na corrida abre o menu: **Corrigir valor** (km e gastos não mudam), **Corrigir km**
  (busca e viagem; gastos com os custos de agora) ou **Excluir corrida** (com confirmação). O botão
  "Corridas Selecionadas" saiu: só entram corridas confirmadas, já marcadas no fim.
- Na aba **Corridas**: filtro **Dia / Semana / Mês** com ◀ ▶. Cada corrida aparece no **mesmo card
  da oferta** (faixa do app com valor, "era R$" e hora; quadrados R$/km | Lucro | Mín. verde, na cor
  da corrida), com os valores e gastos guardados; o Mín. verde usa as regras de agora.
  **Toque no card** marca/desmarca "✅ Realizada" (não marcada fica mais apagada).
- Layout da aba: filtros Dia/Semana/Mês e a data ◀ ▶ ficam fixos; resumo, botão Registrar
  Corrida, "ⓘ Como as corridas entram" (toque abre/fecha a
  explicação) e os cards rolam juntos.
- Resumo das realizadas: corridas e km; número grande **"Fica com você"** (lucro já sem
  combustível, manutenção, deslocamento e deduções) e **"Separar"** (a soma desses descontos, item a item).
- Corridas com mais de **60 dias** são apagadas automaticamente.

## Meta diária e deduções do lucro
- **Configurações → Meta diária e deduções**: meta diária (R$) = quanto você quer que **fique com
  você** no dia, já sem combustível, manutenção e deduções (vazio = sem meta).
- **Deduções do lucro líquido (%)**: descrição + porcentagem (ex.: "Dízimo" 10%); **+ Adicionar
  dedução** cria outra linha, **✕** remove. Calculadas sobre o lucro líquido positivo
  (corridas − combustível − manutenção); a soma deve ficar abaixo de 100%.
- **Aba Corridas → Separar**: total a separar do recebido = combustível + manutenção + deduções,
  detalhado linha a linha (⛽ Combustível, 🔧 Manutenção, 📌 Dízimo (10%)…) e "Recebido R$ x · fica
  com você R$ y" (antes era "Separar p/ abastecer", que somava combustível e manutenção sem dizer).
- **Meta**: no filtro **Dia**,
  "Meta do dia R$ 150,00 · 55%", barra de progresso e "Falta R$ 67,07 no bolso = R$ 74,53 de lucro
  líquido" (o que falta ÷ (1 − soma das %), porque as deduções levam a parte delas). Batida:
  "🎉 Meta batida! Passou R$ x".

## Km rodados (sensor de veículo + GPS) e abastecimentos (tanque cheio)
- **Configurações → Km rodados e abastecimentos → "Contar km rodados automaticamente"** (liga/desliga).
  Pede localização, atividade física, notificações e, em seguida, localização **"Permitir o tempo todo"**.
- O **sensor "em veículo"** do Android (Activity Recognition, quase sem bateria) percebe quando você entra
  num carro e liga o GPS só durante o **trajeto** (notificação "trajeto em andamento" com "Encerrar trajeto").
  O trajeto fecha ao sair do veículo, no botão, ou após 10 min parado.
- Fim do trajeto: < 0,5 km é ignorado; **com corrida confirmada no trajeto → registrado sozinho**; senão,
  notificação **"🚗 Trajeto de 12,4 km · 07:42–08:05 — Registrar | Descartar"** (ex.: carro do trabalho).
  Sem resposta, fica em **"⏳ trajetos pendentes"** na aba Corridas (toque para decidir). Só os registrados
  somam nos km do dia (data do início do trajeto).
- GPS: descarta posição imprecisa (> 35 m), GPS parado (< 25 m), a pé (< 9 km/h) e saltos (> 200 km/h).
- **Aba Corridas → Separar**: "🚗 Deslocamento 34,6 km (rodou 52,3 km) R$ 20,76" = (km registrados − km das
  corridas realizadas) × preço do litro ÷ km/L. **Sem km de GPS no período**, usa a **média do painel**:
  (km do painel entre os 2 últimos tanques cheios − km das corridas realizadas nesse intervalo) ÷ dias,
  "🚗 Deslocamento (média do painel 38 km/dia) R$ 22,80". Também sai do lucro antes das deduções.
- **+ Registrar abastecimento**: data (editável), km do painel, litros, valor e "Encheu o tanque"; toque
  edita/exclui. Do 2º tanque cheio: consumo real, preço médio do litro e painel × GPS no intervalo;
  **"Usar consumo e preço nos custos"** preenche os custos do carro.

## Pausar a leitura pelo app
- Faixa no topo do app: **"📖 Leitura de ofertas ligada · toque para pausar"** /
  **"⏸ Leitura pausada · toque para religar"** (ex.: conferir o histórico da Uber sem o leitor achar
  que são ofertas). Abrir o app não desfaz a pausa; só o mesmo toque religa.

## Diagnóstico para calibrar a leitura
- **Configurações → Diagnóstico da leitura** guarda as **200 leituras** mais recentes (telas e
  **toques** na Uber e no inDrive, inclusive toques sem texto, com classe, id e posição do botão).
- A navegação que só muda números ("50 m", "2,5 km") entra no máximo a cada 30 s, para que as 200
  leituras cubram uma corrida inteira.
- **Exportar relatório (.md)** gera um arquivo Markdown (versão, aparelho, regras e leituras em
  ordem) e abre o compartilhar do Android para anexar no chat.

## Cores do card
- 🟢 **Verde**: R$/km ≥ mínimo **e** lucro líquido ≥ lucro mínimo (voz "Aceitar corrida").
- 🟡 **Amarelo**: R$/km ≥ mínimo, mas lucro líquido abaixo do lucro mínimo (sem voz).
- 🔴 **Vermelho**: R$/km abaixo do mínimo ou nota do passageiro abaixo da mínima.
- Sem lucro mínimo configurado (ou sem custos do carro), vale só o R$/km.
- Verdes e amarelas vão para a aba Corridas (🟢/🟡).
- Abas na ordem: **Corridas** (abre primeiro), **Configurações**, **Simulador**.

## Taxas das plataformas
- **Uber**: o valor do card **já é o ganho do motorista** (taxa da Uber descontada). Conferido em
  05/11: a soma dos cards do dia (R$ 86,18) bate com os ganhos da Uber na quinta (R$ 86,19).
- **inDrive**: o card mostra o valor que o passageiro paga; a taxa (padrão **10%**, ajustável em
  Configurações) sai depois. O app desconta essa taxa no R$/km, no lucro ("Líquido R$ x") e no
  histórico, e o **Mín. verde** do card passa a ser o valor que o passageiro precisa pagar já com a taxa.

## Encerrar
O botão **Encerrar** pausa a leitura (serviço ocioso, sem OCR nem leitor na tela) e fecha o app.
A acessibilidade continua ativada, porque o Android não deixa o app religá-la sozinho;
abrir o app retoma a leitura automaticamente.

## Limitações conhecidas (MVP)
- Na **lista** do inDrive não aparece a distância até o passageiro; o cálculo usa só o trajeto até você abrir a oferta.
- Se a Uber renderizar o cartão sem texto acessível em alguma versão, será preciso um modo OCR de reserva.
- Na lista do inDrive com várias ofertas, é lida a primeira.

## Adicionar outro app (ex.: 99)
1. Nova entrada em `Platform` com o pacote do app.
2. Nova classe `XxxOfferParser : RideOfferParser` + teste com os textos da tela.
3. Registrar em `AppContainer`.

## Atualização pelo próprio app
Ao abrir o Minha Corrida, ele consulta a release `ultima-versao` no GitHub. Havendo versão nova,
aparece **"Nova versão disponível"** com o botão **Atualizar**: o app baixa o APK e abre o
instalador do Android (o Android sempre pede um toque de confirmação em "Instalar").

Requisitos:
- Repositório **público** (o app consulta o GitHub sem login).
- Segredos **SIGNING_KEYSTORE_BASE64** (a chave de assinatura em base64) e
  **SIGNING_STORE_PASSWORD** em *Settings → Secrets and variables → Actions*. A chave nunca
  vai para o repositório; sem ela, cada APK sai com assinatura diferente e o Android recusa
  instalar por cima.

## Gerar o APK sem computador
A cada envio para `main`, o GitHub Actions roda os testes e publica o APK em
**Releases → ultima-versao → MinhaCorrida.apk**.
