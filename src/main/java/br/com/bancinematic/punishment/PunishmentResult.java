package br.com.bancinematic.punishment;

/** Outcome of a punishment change that runs on the database thread. */
public enum PunishmentResult {
    SUCCESS,
    /** The player already has this punishment (ban/mute only). */
    ALREADY_ACTIVE,
    /** There was no active punishment to remove (unban/unmute only). */
    NOT_ACTIVE,
    STORAGE_ERROR
}
