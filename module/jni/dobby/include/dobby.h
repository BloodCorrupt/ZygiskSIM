#ifndef DOBBY_H
#define DOBBY_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

int DobbyHook(void *function_address, void *replace_call, void **origin_call);
int DobbyDestroy(void *function_address);
void *DobbySymbolResolver(const char *image_name, const char *symbol_name);

#ifdef __cplusplus
}
#endif

#endif // DOBBY_H
