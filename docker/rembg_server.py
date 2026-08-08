# Minimal HTTP wrapper around rembg's core library, run as a long-lived
# sidecar (see Dockerfile ENTRYPOINT) so the ONNX model stays loaded in
# memory across requests instead of being reloaded per call.
#
# Deliberately NOT using `rembg s` (the package's own CLI server): that
# command lives behind rembg.cli, and rembg/commands/__init__.py imports
# every subcommand eagerly, which drags in the "cli" extra's full dependency
# tree -- including gradio, needed only for the unrelated GUI subcommand.
# On the production VPS that was enough extra memory pressure to make the
# sidecar fail to start. Importing the library directly (`from rembg import
# remove, new_session`) never touches rembg.cli, so none of that is pulled
# in -- just rembg + onnxruntime + fastapi/uvicorn/python-multipart.
from fastapi import FastAPI, File, UploadFile
from fastapi.responses import Response
from rembg import remove, new_session

app = FastAPI()
_session = new_session("u2net")


@app.post("/api/remove")
async def remove_background(file: UploadFile = File(...)):
    data = await file.read()
    output = remove(data, session=_session)
    return Response(content=output, media_type="image/png")
