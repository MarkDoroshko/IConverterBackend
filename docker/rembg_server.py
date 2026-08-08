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
#
# Uses "u2netp" (the small/portable U^2-Net variant, ~4.7MB of weights vs.
# u2net's 176MB) rather than the default "u2net": the production host is a
# ~1.9GB shared VPS with a dozen other containers already running, and even
# after capping input resolution, u2net's own memory footprint alone was
# still enough to get OOM-killed. u2netp trades some cutout accuracy for a
# meaningfully smaller footprint -- necessary here, not optional.
from fastapi import FastAPI, File, UploadFile
from fastapi.responses import Response
from rembg import remove, new_session

app = FastAPI()
_session = new_session("u2netp")


@app.post("/api/remove")
async def remove_background(file: UploadFile = File(...)):
    data = await file.read()
    output = remove(data, session=_session)
    return Response(content=output, media_type="image/png")
