#ifndef VPN233_BRIDGE_H
#define VPN233_BRIDGE_H

#include <stdlib.h>

int c_protect_socket(int fd);
char *c_query_socket_pkg(int protocol, const char *src, const char *dst);
void c_on_log(const char *level, const char *msg);

#endif
