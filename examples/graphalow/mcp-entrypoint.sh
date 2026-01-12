#!/bin/sh
# Generate /.env from environment variables
# Add new keys here in the heredoc below

cat > /.mcp-secrets.env << EOF
google-maps-comprehensive.api_key=${GOOGLE_MAPS_API_KEY}
tavily.api_token=${TAVILY_API_KEY}
openweather.owm_api_key=${OPEN_WEATHER_API_KEY}
EOF

# Execute the gateway with all arguments passed to this script
exec /docker-mcp gateway run "$@"
