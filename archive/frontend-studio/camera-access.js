// Cancellation cannot dismiss the browser's permission prompt. It invalidates
// that request, so a late permission grant immediately releases its tracks.
export class CameraAccess {
  #revision = 0;

  cancel() {
    this.#revision += 1;
  }

  begin() {
    const revision = ++this.#revision;
    const current = () => revision === this.#revision;
    return {
      current,
      async open(getStream) {
        try {
          const stream = await getStream();
          if (!current()) {
            stream.getTracks().forEach((track) => track.stop());
            return null;
          }
          return stream;
        } catch (error) {
          if (!current()) return null;
          throw error;
        }
      },
    };
  }
}
