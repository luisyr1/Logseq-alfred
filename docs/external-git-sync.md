# Grafos con sincronización Git externa

Cuando un servicio externo coordina los commits, pulls y pushes de un grafo,
activa esta opción en su repositorio:

```bash
git config --local logseq.external-sync true
```

Logseq Alfred omitirá sus autocommits periódicos y al cerrar **solo en ese
grafo**, aunque el ajuste global de autocommit esté activado. Seguirá guardando
los archivos normalmente. El servicio externo es responsable de los commits y
de su subida. Esta opción no instala ni inicia dicho servicio.

Para devolver los autocommits a la aplicación:

```bash
git config --local --unset logseq.external-sync
```

El ajuste es local a cada checkout y debe configurarse en cada equipo que use
un sincronizador externo. No se comparte al hacer push del contenido del grafo.

En grafos sin este ajuste, las peticiones de autocommit que coincidan en el
tiempo se agrupan en una única operación por grafo. Si hay cambios preparados
en el índice, el autocommit los respeta y espera a que se gestionen manualmente.
Si no se puede leer la configuración o comprobar el índice, la app muestra el
error y no intenta un commit a ciegas.

Los comandos Git manuales continúan siendo responsabilidad de quien los
invoca. No deben ejecutarse simultáneamente con una sincronización activa.

Las pruebas de esta política se ejecutan con:

```bash
node --test scripts/tests/git-sync-policy.test.cjs
```

El workflow de Alfred las ejecuta en Linux y en ambas arquitecturas de macOS
antes de compilar y publicar los instaladores.
