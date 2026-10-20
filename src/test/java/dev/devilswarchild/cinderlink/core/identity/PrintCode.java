package dev.devilswarchild.cinderlink.core.identity;

import java.nio.file.Path;

/** Prints the Cinder Code stored in a CinderLink folder. For in-game testing. */
public final class PrintCode {
    public static void main(String[] args) throws Exception {
        System.out.println(Identity.loadOrCreate(Path.of(args[0])).code().format());
    }
}
