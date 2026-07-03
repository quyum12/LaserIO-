package com.direwolf20.laserio.integration.mekanism.util;

import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;

import java.util.Objects;

public class ChemicalStackKey {
    public Chemical<?> chemical;
    private int hash;

    public ChemicalStackKey(ChemicalStack<?> stack) {
        this.chemical = stack.getType().getChemical();
        this.hash = Objects.hash(chemical);
    }

    public ChemicalStackKey() {}

    public ChemicalStackKey set(ChemicalStack<?> stack) {
        this.chemical = stack.getType().getChemical();
        this.hash = Objects.hash(chemical);
        return this;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ChemicalStackKey other) {
            return other.chemical == this.chemical;
        }
        return false;
    }
}