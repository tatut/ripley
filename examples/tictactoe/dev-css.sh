#!/bin/sh
NODE_ENV=dev npx postcss resources/styles.css -o resources/public/tictactoe.css
