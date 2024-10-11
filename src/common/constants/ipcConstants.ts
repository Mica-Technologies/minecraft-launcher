/**
 * Application-wide IPC constants.
 *
 * @since 2025.0.0
 * @author Mica Technologies
 */
export const ipcConstants = {
  F2B_APP_OP_SHUTDOWN: 'f2b:app.op.shutdown',
  F2B_APP_OP_RESTART: 'f2b:app.op.restart',
  F2B_PACKS_DISCOVER_GET_ALL: 'f2b:packs.discover.getAll',
  B2F_PACKS_DISCOVER_ALL_DATA: 'b2f:packs.discover.allData',
} as const;
