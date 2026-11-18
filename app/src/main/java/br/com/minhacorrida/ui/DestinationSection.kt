package br.com.minhacorrida.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import br.com.minhacorrida.R
import br.com.minhacorrida.core.destination.DestinationSettingsRepository
import br.com.minhacorrida.core.neighborhood.BrazilianStates
import br.com.minhacorrida.core.neighborhood.CityChoice
import br.com.minhacorrida.core.neighborhood.NeighborhoodMark
import br.com.minhacorrida.core.parser.NumberParser
import br.com.minhacorrida.infra.PlacesOnline
import br.com.minhacorrida.infra.SharedPrefsNeighborhoodRepository
import java.util.concurrent.ExecutorService

/**
 * Seção "Destino e busca" das Configurações (pedido de 17/11):
 * - estado (lista fixa) e município (IBGE; digita as primeiras letras e escolhe);
 * - bairros da cidade: busca no mapa (OpenStreetMap), resumo das marcações e a tela de marcar;
 * - aviso de bairros novos lidos nas ofertas (e um ponto na aba Configurações);
 * - limite da busca longa (gravado no Salvar, [save]).
 */
class DestinationSection(
    private val activity: Activity,
    private val settings: DestinationSettingsRepository,
    private val neighborhoods: SharedPrefsNeighborhoodRepository,
    private val places: PlacesOnline,
    private val io: ExecutorService,
    /** Quantos bairros novos esperam marcação (para o ponto na aba). */
    private val onNewCount: (Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val cbEnabled: CheckBox = activity.findViewById(R.id.cbDestination)
    private val spUf: Spinner = activity.findViewById(R.id.spUf)
    private val acCity: AutoCompleteTextView = activity.findViewById(R.id.acCity)
    private val tvCityStatus: TextView = activity.findViewById(R.id.tvCityStatus)
    private val tvSummary: TextView = activity.findViewById(R.id.tvBairrosSummary)
    private val tvNew: TextView = activity.findViewById(R.id.tvNewBairros)
    private val etPickupMax: EditText = activity.findViewById(R.id.etPickupMax)
    private val picker = NeighborhoodPicker(activity, neighborhoods) { refresh() }
    private var cities: List<PlacesOnline.City> = emptyList()
    private var bindingUf = false

    fun bind() {
        DecimalMask.attach(etPickupMax, 1)
        val current = settings.get()
        cbEnabled.isChecked = current.enabled
        DecimalMask.set(etPickupMax, current.maxPickupKm)
        cbEnabled.setOnCheckedChangeListener { _, checked -> settings.save(settings.get().copy(enabled = checked)) }

        val labels = BrazilianStates.ALL.map { "${it.second} (${it.first})" }
        spUf.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
        val city = neighborhoods.city()
        val ufIndex = BrazilianStates.ALL.indexOfFirst { it.first == (city?.uf ?: DEFAULT_UF) }.coerceAtLeast(0)
        bindingUf = true
        spUf.setSelection(ufIndex)
        spUf.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (bindingUf) {
                    bindingUf = false
                } else {
                    acCity.setText("")
                }
                loadCities(BrazilianStates.ALL[position].first)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        city?.let { acCity.setText(it.name) }
        acCity.setOnItemClickListener { parent, _, position, _ ->
            val name = parent.getItemAtPosition(position) as String
            chooseCity(name)
        }

        activity.findViewById<Button>(R.id.btnMarkBairros).setOnClickListener { picker.show(onlyNew = false) }
        activity.findViewById<Button>(R.id.btnLoadBairros).setOnClickListener { loadNeighborhoods() }
        tvNew.setOnClickListener { picker.show(onlyNew = true) }
        refresh()
    }

    /** Atualiza resumo, aviso de bairros novos e a cidade (chamar ao voltar para o app). */
    fun refresh() {
        try {
            val city = neighborhoods.city()
            tvCityStatus.text = city?.let { activity.getString(R.string.dest_city_chosen, it.name, it.uf) }
                ?: activity.getString(R.string.dest_city_none)
            val list = neighborhoods.all()
            tvSummary.visibility = if (city == null) View.GONE else View.VISIBLE
            if (city != null) {
                tvSummary.text = activity.getString(
                    R.string.dest_bairros_summary, city.name, list.size,
                    list.count { it.mark == NeighborhoodMark.GOOD }, list.count { it.mark == NeighborhoodMark.FAR },
                    list.count { it.mark == NeighborhoodMark.AVOID }, list.count { it.mark == null },
                )
            }
            val news = list.filter { it.isNew && it.mark == null }
            tvNew.visibility = if (news.isEmpty()) View.GONE else View.VISIBLE
            tvNew.text = activity.getString(R.string.dest_new_bairros, news.size, news.take(3).joinToString(", ") { it.name })
            onNewCount(news.size)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Falha ao mostrar os bairros", e)
        }
    }

    /**
     * Grava o limite da busca (chamado pelo Salvar das Configurações).
     * @return false se o valor for inválido.
     */
    fun save(): Boolean {
        val text = etPickupMax.text.toString().trim()
        val max = if (text.isEmpty()) {
            null
        } else {
            NumberParser.parse(text)?.takeIf { it.signum() > 0 }?.setScale(1, java.math.RoundingMode.HALF_UP) ?: return false
        }
        settings.save(settings.get().copy(maxPickupKm = max))
        return true
    }

    private fun loadCities(uf: String) {
        tvCityStatus.text = activity.getString(R.string.dest_city_loading)
        io.execute {
            val result = runCatching { places.cities(uf) }
            main.post {
                result.onSuccess { list ->
                    cities = list
                    // O filtro do campo já mostra os municípios que começam com as letras digitadas.
                    acCity.setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, list.map { it.name }))
                    refresh()
                }.onFailure { error ->
                    tvCityStatus.text = error.message ?: activity.getString(R.string.dest_city_none)
                }
            }
        }
    }

    private fun chooseCity(name: String) {
        val uf = BrazilianStates.ALL[spUf.selectedItemPosition].first
        val ibge = cities.firstOrNull { it.name == name }?.ibgeId
        neighborhoods.setCity(CityChoice(uf, name, ibge))
        refresh()
        // Cidade nova sem bairros: já busca no mapa.
        if (neighborhoods.all().isEmpty()) loadNeighborhoods()
    }

    private fun loadNeighborhoods() {
        val city = neighborhoods.city() ?: run {
            toast(activity.getString(R.string.dest_city_none))
            return
        }
        tvCityStatus.text = activity.getString(R.string.dest_loading_bairros)
        io.execute {
            val result = runCatching { places.neighborhoods(city) }
            main.post {
                result.onSuccess { found ->
                    neighborhoods.mergeFromMap(found)
                    toast(activity.getString(R.string.dest_loaded_bairros, found.size))
                }.onFailure { error ->
                    toast(error.message ?: activity.getString(R.string.dest_city_none))
                }
                refresh()
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "MC/Destination"
        const val DEFAULT_UF = "SC"
    }
}
