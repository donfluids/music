/* Effective core clock: a chain of dependent integer adds runs at one add per cycle. */
#include <stdio.h>
#include <time.h>
int main(void) {
    for (int rep = 0; rep < 5; rep++) {
        struct timespec a, b; long n = 2000000000L; long x = 0;
        clock_gettime(CLOCK_MONOTONIC, &a);
        for (long i = 0; i < n; i++) __asm__ volatile("add $1, %0" : "+r"(x));
        clock_gettime(CLOCK_MONOTONIC, &b);
        double s = (b.tv_sec - a.tv_sec) + (b.tv_nsec - a.tv_nsec) * 1e-9;
        printf("%.2f GHz (x=%ld)\n", n / s / 1e9, x);
    }
    return 0;
}
