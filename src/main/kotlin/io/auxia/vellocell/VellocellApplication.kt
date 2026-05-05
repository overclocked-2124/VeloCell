package io.auxia.vellocell

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class VellocellApplication

fun main(args: Array<String>) {
	runApplication<VellocellApplication>(*args)
}
