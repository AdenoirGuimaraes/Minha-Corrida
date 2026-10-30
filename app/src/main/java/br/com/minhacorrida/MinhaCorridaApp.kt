package br.com.minhacorrida

import android.app.Application
import br.com.minhacorrida.di.AppContainer

/** Ponto de entrada: cria o container de dependências uma única vez. */
class MinhaCorridaApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
