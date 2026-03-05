package com.aichallenge.day2.agent.domain.repository

interface InvariantConstraintStore {
    fun load(): List<String>
    fun save(constraints: List<String>)
}
