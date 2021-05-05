#!/bin/sh
NODE_ENV=production npx postcss resources/styles.css -o resources/public/tictactoe.css
