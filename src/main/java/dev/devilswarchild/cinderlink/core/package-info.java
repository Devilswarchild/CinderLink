/**
 * Loader-independent engine: identity, DHT presence, encryption, port mapping.
 *
 * RULE: nothing in this package (or below) may import net.minecraft or net.neoforged.
 * Keeping it that way means the 1.20.1 backport only rewrites the client/ layer.
 */
package dev.devilswarchild.cinderlink.core;
