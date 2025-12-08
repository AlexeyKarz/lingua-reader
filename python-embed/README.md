### Python helpers

Install deps:
pip install psycopg2-binary

Run add book:
```
python add_book.py \
--work "Faust (Part I)" --year 1806 --author "Goethe" \
--edition "lang=de,file=classpath:books/faust_de_1.epub" \
--edition "lang=en,file=classpath:books/faust_en_1.epub" \
--align-src de --align-tgt en
```

DB env (example):
export MLR_DB_DSN="postgresql://mlr:mlr@localhost:5432/mlr"
