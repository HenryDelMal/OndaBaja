// Desktop numerical test of the same Vocos backend compiled into Android.
#include "vocos.h"
#include <fstream>
#include <iostream>
#include <chrono>
int main(int argc,char** argv) {
    try {
        if(argc!=6) throw std::runtime_error("Usage: fixture MODEL TOKENS FRAMES CODEBOOKS PCM");
        vocos::codec decoder(argv[1]);
        vocos::tokens codes{std::stoul(argv[3]),uint32_t(std::stoul(argv[4])),{}};
        codes.indices.resize(codes.frames*codes.codebooks);
        std::ifstream input(argv[2],std::ios::binary);
        input.read(reinterpret_cast<char*>(codes.indices.data()),codes.indices.size()*2);
        if(!input) throw std::runtime_error("Truncated tokens");
        auto start=std::chrono::steady_clock::now();
        auto pcm=decoder.decode(codes);
        std::ofstream output(argv[5],std::ios::binary);
        output.write(reinterpret_cast<const char*>(pcm.data()),pcm.size()*4);
        if(!output) throw std::runtime_error("Cannot write PCM");
        std::cout << pcm.size() << " samples, "
            << std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count() << " s\n";
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n'; return 1; }
}
