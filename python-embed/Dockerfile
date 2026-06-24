FROM python:3.11-slim

ENV PIP_NO_CACHE_DIR=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/models/huggingface \
    SENTENCE_TRANSFORMERS_HOME=/models/sentence-transformers

WORKDIR /work

COPY requirements.txt /work/python-embed/requirements.txt
RUN python -m pip install --upgrade pip \
    && python -m pip install -r /work/python-embed/requirements.txt

COPY *.py README.md /work/python-embed/

ENTRYPOINT ["python", "python-embed/add_book.py"]
