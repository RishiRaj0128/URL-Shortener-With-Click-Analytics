import React, { useState, useEffect } from 'react';
import { 
  Link2, Copy, Trash2, Download, LogOut, BarChart3, Clock, 
  Globe, Monitor, Laptop, KeyRound, Mail, Plus, CheckCircle, AlertCircle,
  Activity, ShieldAlert, Cpu, Server, Zap, RefreshCw, ExternalLink
} from 'lucide-react';
import { 
  LineChart, Line, BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell 
} from 'recharts';

const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';

// ISO 2-letter Country Codes to Names and Flag Emojis mapping
const COUNTRY_MAP = {
  'AD': { name: 'Andorra', flag: '🇦🇩' },
  'AE': { name: 'United Arab Emirates', flag: '🇦🇪' },
  'AF': { name: 'Afghanistan', flag: '🇦🇫' },
  'AG': { name: 'Antigua and Barbuda', flag: '🇦🇬' },
  'AI': { name: 'Anguilla', flag: '🇦🇮' },
  'AL': { name: 'Albania', flag: '🇦🇱' },
  'AM': { name: 'Armenia', flag: '🇦🇲' },
  'AO': { name: 'Angola', flag: '🇦🇴' },
  'AR': { name: 'Argentina', flag: '🇦🇷' },
  'AS': { name: 'American Samoa', flag: '🇦🇸' },
  'AT': { name: 'Austria', flag: '🇦🇹' },
  'AU': { name: 'Australia', flag: '🇦🇺' },
  'AW': { name: 'Aruba', flag: '🇦🇼' },
  'AX': { name: 'Åland Islands', flag: '🇦🇽' },
  'AZ': { name: 'Azerbaijan', flag: '🇦🇿' },
  'BA': { name: 'Bosnia and Herzegovina', flag: '🇧🇦' },
  'BB': { name: 'Barbados', flag: '🇧🇧' },
  'BD': { name: 'Bangladesh', flag: '🇧🇩' },
  'BE': { name: 'Belgium', flag: '🇧🇪' },
  'BF': { name: 'Burkina Faso', flag: '🇧🇫' },
  'BG': { name: 'Bulgaria', flag: '🇧🇬' },
  'BH': { name: 'Bahrain', flag: '🇧🇭' },
  'BI': { name: 'Burundi', flag: '🇧🇮' },
  'BJ': { name: 'Benin', flag: '🇧🇯' },
  'BL': { name: 'Saint Barthélemy', flag: '🇧🇱' },
  'BM': { name: 'Bermuda', flag: '🇧🇲' },
  'BN': { name: 'Brunei', flag: '🇧🇳' },
  'BO': { name: 'Bolivia', flag: '🇧🇴' },
  'BQ': { name: 'Caribbean Netherlands', flag: '🇧🇶' },
  'BR': { name: 'Brazil', flag: '🇧🇷' },
  'BS': { name: 'Bahamas', flag: '🇧🇸' },
  'BT': { name: 'Bhutan', flag: '🇧🇹' },
  'BV': { name: 'Bouvet Island', flag: '🇧🇻' },
  'BW': { name: 'Botswana', flag: '🇧🇼' },
  'BY': { name: 'Belarus', flag: '🇧🇾' },
  'BZ': { name: 'Belize', flag: '🇧🇿' },
  'CA': { name: 'Canada', flag: '🇨🇦' },
  'CC': { name: 'Cocos (Keeling) Islands', flag: '🇨🇨' },
  'CD': { name: 'DR Congo', flag: '🇨🇩' },
  'CF': { name: 'Central African Republic', flag: '🇨🇫' },
  'CG': { name: 'Congo', flag: '🇨🇬' },
  'CH': { name: 'Switzerland', flag: '🇨🇭' },
  'CI': { name: 'Côte d\'Ivoire', flag: '🇨🇮' },
  'CK': { name: 'Cook Islands', flag: '🇨🇰' },
  'CL': { name: 'Chile', flag: '🇨🇱' },
  'CM': { name: 'Cameroon', flag: '🇨🇲' },
  'CN': { name: 'China', flag: '🇨🇳' },
  'CO': { name: 'Colombia', flag: '🇨🇴' },
  'CR': { name: 'Costa Rica', flag: '🇨🇷' },
  'CU': { name: 'Cuba', flag: '🇨🇺' },
  'CV': { name: 'Cape Verde', flag: '🇨🇻' },
  'CW': { name: 'Curaçao', flag: '🇨🇼' },
  'CX': { name: 'Christmas Island', flag: '🇨🇽' },
  'CY': { name: 'Cyprus', flag: '🇨🇾' },
  'CZ': { name: 'Czechia', flag: '🇨🇿' },
  'DE': { name: 'Germany', flag: '🇩🇪' },
  'DJ': { name: 'Djibouti', flag: '🇩🇯' },
  'DK': { name: 'Denmark', flag: '🇩🇰' },
  'DM': { name: 'Dominica', flag: '🇩🇲' },
  'DO': { name: 'Dominican Republic', flag: '🇩🇴' },
  'DZ': { name: 'Algeria', flag: '🇩🇿' },
  'EC': { name: 'Ecuador', flag: '🇪🇨' },
  'EE': { name: 'Estonia', flag: '🇪🇪' },
  'EG': { name: 'Egypt', flag: '🇪🇬' },
  'EH': { name: 'Western Sahara', flag: '🇪🇭' },
  'ER': { name: 'Eritrea', flag: '🇪🇷' },
  'ES': { name: 'Spain', flag: '🇪🇸' },
  'ET': { name: 'Ethiopia', flag: '🇪🇹' },
  'FI': { name: 'Finland', flag: '🇫🇮' },
  'FJ': { name: 'Fiji', flag: '🇫🇯' },
  'FK': { name: 'Falkland Islands', flag: '🇫🇰' },
  'FM': { name: 'Micronesia', flag: '🇫🇲' },
  'FO': { name: 'Faroe Islands', flag: '🇫🇴' },
  'FR': { name: 'France', flag: '🇫🇷' },
  'GA': { name: 'Gabon', flag: '🇬🇦' },
  'GB': { name: 'United Kingdom', flag: '🇬🇧' },
  'GD': { name: 'Grenada', flag: '🇬🇩' },
  'GE': { name: 'Georgia', flag: '🇬🇪' },
  'GF': { name: 'French Guiana', flag: '🇬🇫' },
  'GG': { name: 'Guernsey', flag: '🇬🇬' },
  'GH': { name: 'Ghana', flag: '🇬🇭' },
  'GI': { name: 'Gibraltar', flag: '🇬🇮' },
  'GL': { name: 'Greenland', flag: '🇬🇱' },
  'GM': { name: 'Gambia', flag: '🇬🇲' },
  'GN': { name: 'Guinea', flag: '🇬🇳' },
  'GP': { name: 'Guadeloupe', flag: '🇬🇵' },
  'GQ': { name: 'Equatorial Guinea', flag: '🇬🇶' },
  'GR': { name: 'Greece', flag: '🇬🇷' },
  'GS': { name: 'South Georgia', flag: '🇬🇸' },
  'GT': { name: 'Guatemala', flag: '🇬🇹' },
  'GU': { name: 'Guam', flag: '🇬🇺' },
  'GW': { name: 'Guinea-Bissau', flag: '🇬🇼' },
  'GY': { name: 'Guyana', flag: '🇬🇾' },
  'HK': { name: 'Hong Kong', flag: '🇭🇰' },
  'HM': { name: 'Heard & McDonald', flag: '🇭🇲' },
  'HN': { name: 'Honduras', flag: '🇭🇳' },
  'HR': { name: 'Croatia', flag: '🇭🇷' },
  'HT': { name: 'Haiti', flag: '🇭🇹' },
  'HU': { name: 'Hungary', flag: '🇭🇺' },
  'ID': { name: 'Indonesia', flag: '🇮🇩' },
  'IE': { name: 'Ireland', flag: '🇮🇪' },
  'IL': { name: 'Israel', flag: '🇮🇱' },
  'IM': { name: 'Isle of Man', flag: '🇮🇲' },
  'IN': { name: 'India', flag: '🇮🇳' },
  'IO': { name: 'British Indian Ocean Territory', flag: '🇮🇴' },
  'IQ': { name: 'Iraq', flag: '🇮🇶' },
  'IR': { name: 'Iran', flag: '🇮🇷' },
  'IS': { name: 'Iceland', flag: '🇮🇸' },
  'IT': { name: 'Italy', flag: '🇮🇹' },
  'JE': { name: 'Jersey', flag: '🇯🇪' },
  'JM': { name: 'Jamaica', flag: '🇯🇲' },
  'JO': { name: 'Jordan', flag: '🇯🇴' },
  'JP': { name: 'Japan', flag: '🇯🇵' },
  'KE': { name: 'Kenya', flag: '🇰🇪' },
  'KG': { name: 'Kyrgyzstan', flag: '🇰🇬' },
  'KH': { name: 'Cambodia', flag: '🇰🇭' },
  'KI': { name: 'Kiribati', flag: '🇰🇮' },
  'KM': { name: 'Comoros', flag: '🇰🇲' },
  'KN': { name: 'Saint Kitts and Nevis', flag: '🇰🇳' },
  'KP': { name: 'North Korea', flag: '🇰🇵' },
  'KR': { name: 'South Korea', flag: '🇰🇷' },
  'KW': { name: 'Kuwait', flag: '🇰🇼' },
  'KY': { name: 'Cayman Islands', flag: '🇰🇾' },
  'KZ': { name: 'Kazakhstan', flag: '🇰🇿' },
  'LA': { name: 'Laos', flag: '🇱🇦' },
  'LB': { name: 'Lebanon', flag: '🇱🇧' },
  'LC': { name: 'Saint Lucia', flag: '🇱🇨' },
  'LI': { name: 'Liechtenstein', flag: '🇱🇮' },
  'LK': { name: 'Sri Lanka', flag: '🇱🇰' },
  'LR': { name: 'Liberia', flag: '🇱🇷' },
  'LS': { name: 'Lesotho', flag: '🇱🇸' },
  'LT': { name: 'Lithuania', flag: '🇱🇹' },
  'LU': { name: 'Luxembourg', flag: '🇱🇺' },
  'LV': { name: 'Latvia', flag: '🇱🇻' },
  'LY': { name: 'Libya', flag: '🇱🇾' },
  'MA': { name: 'Morocco', flag: '🇲🇦' },
  'MC': { name: 'Monaco', flag: '🇲🇨' },
  'MD': { name: 'Moldova', flag: '🇲🇩' },
  'ME': { name: 'Montenegro', flag: '🇲🇪' },
  'MF': { name: 'Saint Martin', flag: '🇲🇫' },
  'MG': { name: 'Madagascar', flag: '🇲🇬' },
  'MH': { name: 'Marshall Islands', flag: '🇲🇭' },
  'MK': { name: 'North Macedonia', flag: '🇲🇰' },
  'ML': { name: 'Mali', flag: '🇲🇱' },
  'MM': { name: 'Myanmar', flag: '🇲🇲' },
  'MN': { name: 'Mongolia', flag: '🇲🇳' },
  'MO': { name: 'Macao', flag: '🇲🇴' },
  'MP': { name: 'Northern Mariana Islands', flag: '🇲🇵' },
  'MQ': { name: 'Martinique', flag: '🇲🇶' },
  'MR': { name: 'Mauritania', flag: '🇲🇷' },
  'MS': { name: 'Montserrat', flag: '🇲🇸' },
  'MT': { name: 'Malta', flag: '🇲🇹' },
  'MU': { name: 'Mauritius', flag: '🇲🇺' },
  'MV': { name: 'Maldives', flag: '🇲🇻' },
  'MW': { name: 'Malawi', flag: '🇲🇼' },
  'MX': { name: 'Mexico', flag: '🇲🇽' },
  'MY': { name: 'Malaysia', flag: '🇲🇾' },
  'MZ': { name: 'Mozambique', flag: '🇲🇿' },
  'NA': { name: 'Namibia', flag: '🇳🇦' },
  'NC': { name: 'New Caledonia', flag: '🇳🇨' },
  'NE': { name: 'Niger', flag: '🇳🇪' },
  'NF': { name: 'Norfolk Island', flag: '🇳🇫' },
  'NG': { name: 'Nigeria', flag: '🇳🇬' },
  'NI': { name: 'Nicaragua', flag: '🇳🇮' },
  'NL': { name: 'Netherlands', flag: '🇳🇱' },
  'NO': { name: 'Norway', flag: '🇳🇴' },
  'NP': { name: 'Nepal', flag: '🇳🇵' },
  'NR': { name: 'Nauru', flag: '🇳🇷' },
  'NU': { name: 'Niue', flag: '🇳🇺' },
  'NZ': { name: 'New Zealand', flag: '🇳🇿' },
  'OM': { name: 'Oman', flag: '🇴🇲' },
  'PA': { name: 'Panama', flag: '🇵🇦' },
  'PE': { name: 'Peru', flag: '🇵🇪' },
  'PF': { name: 'French Polynesia', flag: '🇵🇫' },
  'PG': { name: 'Papua New Guinea', flag: '🇵🇬' },
  'PH': { name: 'Philippines', flag: '🇵🇭' },
  'PK': { name: 'Pakistan', flag: '🇵🇰' },
  'PL': { name: 'Poland', flag: '🇵🇱' },
  'PM': { name: 'Saint Pierre & Miquelon', flag: '🇵🇲' },
  'PN': { name: 'Pitcairn Islands', flag: '🇵🇳' },
  'PR': { name: 'Puerto Rico', flag: '🇵🇷' },
  'PS': { name: 'Palestine', flag: '🇵🇸' },
  'PT': { name: 'Portugal', flag: '🇵🇹' },
  'PW': { name: 'Palau', flag: '🇵🇼' },
  'PY': { name: 'Paraguay', flag: '🇵🇾' },
  'QA': { name: 'Qatar', flag: '🇶🇦' },
  'RE': { name: 'Réunion', flag: '🇷🇪' },
  'RO': { name: 'Romania', flag: '🇷🇴' },
  'RS': { name: 'Serbia', flag: '🇷🇸' },
  'RU': { name: 'Russia', flag: '🇷🇺' },
  'RW': { name: 'Rwanda', flag: '🇷🇼' },
  'SA': { name: 'Saudi Arabia', flag: '🇸🇦' },
  'SB': { name: 'Solomon Islands', flag: '🇸🇧' },
  'SC': { name: 'Seychelles', flag: '🇸🇨' },
  'SD': { name: 'Sudan', flag: '🇸🇩' },
  'SE': { name: 'Sweden', flag: '🇸🇪' },
  'SG': { name: 'Singapore', flag: '🇸🇬' },
  'SH': { name: 'Saint Helena', flag: '🇸🇭' },
  'SI': { name: 'Slovenia', flag: '🇸🇮' },
  'SJ': { name: 'Svalbard & Jan Mayen', flag: '🇸🇯' },
  'SK': { name: 'Slovakia', flag: '🇸🇰' },
  'SL': { name: 'Sierra Leone', flag: '🇸🇱' },
  'SM': { name: 'San Marino', flag: '🇸🇲' },
  'SN': { name: 'Senegal', flag: '🇸🇳' },
  'SO': { name: 'Somalia', flag: '🇸🇴' },
  'SR': { name: 'Suriname', flag: '🇸🇷' },
  'SS': { name: 'South Sudan', flag: '🇸🇸' },
  'ST': { name: 'São Tomé & Príncipe', flag: '🇸🇹' },
  'SV': { name: 'El Salvador', flag: '🇸🇻' },
  'SX': { name: 'Sint Maarten', flag: '🇸🇽' },
  'SY': { name: 'Syria', flag: '🇸🇾' },
  'SZ': { name: 'Eswatini', flag: '🇸🇿' },
  'TC': { name: 'Turks & Caicos Islands', flag: '🇹🇨' },
  'TD': { name: 'Chad', flag: '🇹🇩' },
  'TF': { name: 'French Southern Territories', flag: '🇹🇫' },
  'TG': { name: 'Togo', flag: '🇹🇬' },
  'TH': { name: 'Thailand', flag: '🇹🇭' },
  'TJ': { name: 'Tajikistan', flag: '🇹🇯' },
  'TK': { name: 'Tokelau', flag: '🇹🇰' },
  'TL': { name: 'Timor-Leste', flag: '🇹🇱' },
  'TM': { name: 'Turkmenistan', flag: '🇹🇲' },
  'TN': { name: 'Tunisia', flag: '🇹🇳' },
  'TO': { name: 'Tonga', flag: '🇹🇴' },
  'TR': { name: 'Turkey', flag: '🇹🇷' },
  'TT': { name: 'Trinidad and Tobago', flag: '🇹🇹' },
  'TV': { name: 'Tuvalu', flag: '🇹🇻' },
  'TW': { name: 'Taiwan', flag: '🇹🇼' },
  'TZ': { name: 'Tanzania', flag: '🇹🇿' },
  'UA': { name: 'Ukraine', flag: '🇺🇦' },
  'UG': { name: 'Uganda', flag: '🇺🇬' },
  'UM': { name: 'U.S. Outlying Islands', flag: '🇺🇲' },
  'US': { name: 'United States', flag: '🇺🇸' },
  'UY': { name: 'Uruguay', flag: '🇺🇾' },
  'UZ': { name: 'Uzbekistan', flag: '🇺🇿' },
  'VA': { name: 'Vatican City', flag: '🇻🇦' },
  'VC': { name: 'St. Vincent & Grenadines', flag: '🇻🇨' },
  'VE': { name: 'Venezuela', flag: '🇻🇪' },
  'VG': { name: 'British Virgin Islands', flag: '🇻🇬' },
  'VI': { name: 'U.S. Virgin Islands', flag: '🇻🇮' },
  'VN': { name: 'Vietnam', flag: '🇻🇳' },
  'VU': { name: 'Vanuatu', flag: '🇻🇺' },
  'WF': { name: 'Wallis and Futuna', flag: '🇼🇫' },
  'WS': { name: 'Samoa', flag: '🇼🇸' },
  'YE': { name: 'Yemen', flag: '🇾🇪' },
  'YT': { name: 'Mayotte', flag: '🇾🇹' },
  'ZA': { name: 'South Africa', flag: '🇿🇦' },
  'ZM': { name: 'Zambia', flag: '🇿🇲' },
  'ZW': { name: 'Zimbabwe', flag: '🇿🇼' }
};

const getCountryDetails = (code) => {
  if (!code) return { name: 'Unknown', flag: '🏳️' };
  const upperCode = code.toUpperCase();
  return COUNTRY_MAP[upperCode] || { name: upperCode, flag: '🏳️' };
};

const maskSensitiveUrl = (urlString) => {
  if (!urlString) return '';
  try {
    const url = new URL(urlString);
    const searchParams = url.searchParams;
    let hasChanges = false;
    const sensitiveKeys = [
      'token', 'auth', 'password', 'key', 'secret', 
      'api_key', 'apikey', 'session', 'session_id', 
      'sid', 'pass', 'credential', 'pwd', 'access_token'
    ];
    
    for (const key of searchParams.keys()) {
      const lowerKey = key.toLowerCase();
      const isSensitive = sensitiveKeys.some(s => lowerKey.includes(s));
      if (isSensitive) {
        searchParams.set(key, '●●●●●●●●');
        hasChanges = true;
      }
    }
    
    return hasChanges ? url.toString() : urlString;
  } catch (e) {
    return urlString;
  }
};

export default function App() {
  // Authentication State
  const [token, setToken] = useState(localStorage.getItem('token') || '');
  const [email, setEmail] = useState(localStorage.getItem('email') || '');
  const [isLoginTab, setIsLoginTab] = useState(true);
  const [authEmail, setAuthEmail] = useState('');
  const [authPassword, setAuthPassword] = useState('');
  const [authError, setAuthError] = useState('');
  const [authLoading, setAuthLoading] = useState(false);

  // App Dashboard State
  const [urls, setUrls] = useState([]);
  const [longUrl, setLongUrl] = useState('');
  const [shortenLoading, setShortenLoading] = useState(false);
  const [createdUrl, setCreatedUrl] = useState(null);
  const [selectedCode, setSelectedCode] = useState(null);
  const [analytics, setAnalytics] = useState(null);
  const [analyticsLoading, setAnalyticsLoading] = useState(false);

  // Traffic Intelligence & Self-Healing State
  const [activeTab, setActiveTab] = useState('links'); // 'links' | 'intelligence'
  const [circuitBreakers, setCircuitBreakers] = useState(null);
  const [clusterNodes, setClusterNodes] = useState([]);
  const [anomalies, setAnomalies] = useState([]);
  const [anomalousRollups, setAnomalousRollups] = useState([]);
  const [systemLoading, setSystemLoading] = useState(false);

  // UI Toast State
  const [toast, setToast] = useState(null);

  const fetchSystemIntelligence = async () => {
    try {
      setSystemLoading(true);
      const [cbRes, nodesRes, anomRes, rollupsRes] = await Promise.all([
        fetch(`${API_URL}/api/system/circuit-breakers`).catch(() => null),
        fetch(`${API_URL}/api/system/routing`).catch(() => null),
        fetch(`${API_URL}/api/system/anomalies`).catch(() => null),
        fetch(`${API_URL}/api/system/rollups`).catch(() => null),
      ]);

      if (cbRes && cbRes.ok) setCircuitBreakers(await cbRes.json());
      if (nodesRes && nodesRes.ok) setClusterNodes(await nodesRes.json());
      if (anomRes && anomRes.ok) setAnomalies(await anomRes.json());
      if (rollupsRes && rollupsRes.ok) setAnomalousRollups(await rollupsRes.json());
    } catch (e) {
      console.warn("Failed to fetch system intelligence", e);
    } finally {
      setSystemLoading(false);
    }
  };

  const getShortUrl = (code) => {
    const item = urls.find(u => u.shortCode === code);
    if (item && item.shortUrl) return item.shortUrl;
    if (createdUrl && createdUrl.shortCode === code) return createdUrl.shortUrl;
    return `${API_URL}/${code}`;
  };

  // Auto-clear toast alert
  useEffect(() => {
    if (toast) {
      const timer = setTimeout(() => setToast(null), 3000);
      return () => clearTimeout(timer);
    }
  }, [toast]);

  // Load user's URLs list when authenticated
  useEffect(() => {
    if (token) {
      fetchUrls();
      fetchSystemIntelligence();
    }
  }, [token]);

  // Polling for system intelligence when activeTab === 'intelligence'
  useEffect(() => {
    if (activeTab === 'intelligence') {
      fetchSystemIntelligence();
      const interval = setInterval(fetchSystemIntelligence, 4000);
      return () => clearInterval(interval);
    }
  }, [activeTab]);

  // Fetch detailed analytics when a URL is selected
  useEffect(() => {
    if (selectedCode) {
      fetchAnalytics(selectedCode);
    } else {
      setAnalytics(null);
    }
  }, [selectedCode]);

  const showToast = (message, type = 'success') => {
    setToast({ message, type });
  };

  const handleLogout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('email');
    setToken('');
    setEmail('');
    setUrls([]);
    setSelectedCode(null);
    setCreatedUrl(null);
    showToast('Logged out successfully');
  };

  const handleAuthSubmit = async (e) => {
    e.preventDefault();
    if (!authEmail || !authPassword) {
      setAuthError('Please fill in all fields');
      return;
    }

    setAuthLoading(true);
    setAuthError('');

    const endpoint = isLoginTab ? '/api/auth/login' : '/api/auth/signup';
    try {
      const response = await fetch(`${API_URL}${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: authEmail, password: authPassword }),
      });

      const data = await response.json();
      
      if (!response.ok) {
        throw new Error(data.message || 'Authentication failed');
      }

      localStorage.setItem('token', data.token);
      localStorage.setItem('email', data.email);
      setToken(data.token);
      setEmail(data.email);
      setAuthEmail('');
      setAuthPassword('');
      showToast(isLoginTab ? 'Welcome back!' : 'Account created successfully!');
    } catch (err) {
      setAuthError(err.message);
    } finally {
      setAuthLoading(false);
    }
  };

  const fetchUrls = async () => {
    try {
      const response = await fetch(`${API_URL}/api/urls`, {
        headers: { 'Authorization': `Bearer ${token}` },
      });
      if (response.status === 401) {
        handleLogout();
        return;
      }
      if (!response.ok) throw new Error('Failed to load links');
      const data = await response.json();
      setUrls(data);
    } catch (err) {
      showToast(err.message, 'error');
    }
  };

  const handleShorten = async (e) => {
    e.preventDefault();
    if (!longUrl || !longUrl.trim()) return;

    let targetUrl = longUrl.trim();
    // Auto-prepend https:// if user entered domain without protocol (e.g. google.com)
    if (!/^https?:\/\//i.test(targetUrl) && !/^ftp:\/\//i.test(targetUrl)) {
      targetUrl = 'https://' + targetUrl;
    }

    setShortenLoading(true);
    try {
      const response = await fetch(`${API_URL}/api/shorten`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify({ url: targetUrl }),
      });

      if (response.status === 429) {
        showToast('Too many requests. Continuous rate limiter active.', 'error');
        return;
      }

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        const errorMsg = errorData.errors?.url || errorData.message || 'Failed to shorten URL';
        throw new Error(errorMsg);
      }
      const data = await response.json();
      
      setCreatedUrl(data);
      setLongUrl('');
      showToast('Short link generated!');
      fetchUrls(); // Refresh the list
      setSelectedCode(data.shortCode); // Instantly view analytics
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setShortenLoading(false);
    }
  };

  const fetchAnalytics = async (code) => {
    setAnalyticsLoading(true);
    try {
      const response = await fetch(`${API_URL}/api/analytics/${code}`, {
        headers: { 'Authorization': `Bearer ${token}` },
      });
      if (!response.ok) throw new Error('Failed to load analytics');
      const data = await response.json();
      setAnalytics(data);
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setAnalyticsLoading(false);
    }
  };

  const handleDelete = async (e, code) => {
    e.stopPropagation(); // Avoid selecting the item for analytics
    if (!confirm('Are you sure you want to delete this link?')) return;

    try {
      const response = await fetch(`${API_URL}/api/urls/${code}`, {
        method: 'DELETE',
        headers: { 'Authorization': `Bearer ${token}` },
      });

      if (!response.ok) throw new Error('Failed to delete URL');
      
      showToast('Link deleted');
      if (selectedCode === code) {
        setSelectedCode(null);
      }
      if (createdUrl && createdUrl.shortCode === code) {
        setCreatedUrl(null);
      }
      fetchUrls();
    } catch (err) {
      showToast(err.message, 'error');
    }
  };

  const copyToClipboard = (text) => {
    navigator.clipboard.writeText(text);
    showToast('Copied to clipboard!');
  };

  const downloadQR = (base64Data, shortCode) => {
    const link = document.createElement('a');
    link.href = base64Data;
    link.download = `qrcode-${shortCode}.png`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    showToast('Downloaded QR Code');
  };

  // Auth UI component
  if (!token) {
    return (
      <div className="auth-container">
        <div className="auth-glow-1"></div>
        <div className="auth-glow-2"></div>
        
        <div className="auth-card glass-panel animate-fade-in">
          <div className="auth-header">
            <div className="auth-logo">
              <Link2 size={28} />
            </div>
            <h1 className="auth-title">URL Analytics</h1>
            <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', marginTop: '8px' }}>
              Shorten links & track rich analytics
            </p>
          </div>

          <div className="auth-tabs">
            <button 
              className={`auth-tab ${isLoginTab ? 'active' : ''}`}
              onClick={() => { setIsLoginTab(true); setAuthError(''); }}
            >
              Sign In
            </button>
            <button 
              className={`auth-tab ${!isLoginTab ? 'active' : ''}`}
              onClick={() => { setIsLoginTab(false); setAuthError(''); }}
            >
              Register
            </button>
          </div>

          {authError && (
            <div className="error-banner">
              <AlertCircle size={18} />
              <span>{authError}</span>
            </div>
          )}

          <form onSubmit={handleAuthSubmit}>
            <div className="form-group">
              <label className="form-label">Email Address</label>
              <div style={{ position: 'relative' }}>
                <Mail size={18} style={{ position: 'absolute', left: '16px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
                <input 
                  type="email" 
                  placeholder="name@company.com" 
                  value={authEmail}
                  onChange={(e) => setAuthEmail(e.target.value)}
                  style={{ paddingLeft: '48px', width: '100%' }}
                  required
                />
              </div>
            </div>

            <div className="form-group">
              <label className="form-label">Password</label>
              <div style={{ position: 'relative' }}>
                <KeyRound size={18} style={{ position: 'absolute', left: '16px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
                <input 
                  type="password" 
                  placeholder="••••••••" 
                  value={authPassword}
                  onChange={(e) => setAuthPassword(e.target.value)}
                  style={{ paddingLeft: '48px', width: '100%' }}
                  required
                />
              </div>
            </div>

            <button 
              type="submit" 
              className="btn-primary" 
              style={{ width: '100%', marginTop: '12px', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px' }}
              disabled={authLoading}
            >
              {authLoading ? (
                <span className="spinner"></span>
              ) : isLoginTab ? (
                'Sign In'
              ) : (
                'Create Account'
              )}
            </button>
          </form>
        </div>
      </div>
    );
  }

  // Dashboard UI
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Top Header */}
      <header className="app-header">
        <div className="logo-container">
          <div className="auth-logo" style={{ width: '38px', height: '38px', fontSize: '1.25rem', marginBottom: 0, borderRadius: '10px' }}>
            <Link2 size={20} />
          </div>
          <span className="logo-text">URL Analytics</span>
        </div>

        {/* Global Navigation Tabs */}
        <div className="nav-tabs-container">
          <button 
            className={`nav-tab-btn ${activeTab === 'links' ? 'active' : ''}`}
            onClick={() => setActiveTab('links')}
          >
            <BarChart3 size={15} />
            <span>Links & Analytics</span>
          </button>
          <button 
            className={`nav-tab-btn ${activeTab === 'intelligence' ? 'active' : ''}`}
            onClick={() => { setActiveTab('intelligence'); fetchSystemIntelligence(); }}
          >
            <Activity size={15} />
            <span>Traffic Intelligence & Reliability</span>
            {circuitBreakers && (
              <span className="status-indicator status-closed" style={{ fontSize: '0.7rem' }}>
                <span className="status-dot"></span>
              </span>
            )}
          </button>
        </div>

        <div className="user-nav">
          <span className="user-email">{email}</span>
          <button className="logout-btn" onClick={handleLogout}>
            <LogOut size={16} />
            <span>Sign Out</span>
          </button>
        </div>
      </header>

      {/* Main Workspace */}
      <main className="dashboard-container">
        {activeTab === 'intelligence' ? (
          <div className="intelligence-dashboard">
            {/* KPI Summary Row */}
            <div className="kpi-row">
              <div className="kpi-card">
                <span className="kpi-title"><Cpu size={14} /> ID Generation</span>
                <span className="kpi-value">Snowflake 64-bit</span>
                <span className="kpi-badge badge-success">Zero-Coordination Base62</span>
              </div>

              <div className="kpi-card">
                <span className="kpi-title"><Zap size={14} /> Cache Resilience</span>
                <span className="kpi-value">
                  {circuitBreakers?.redisCache?.state || 'CLOSED'}
                </span>
                <span className={`kpi-badge ${circuitBreakers?.redisCache?.state === 'OPEN' ? 'badge-danger' : 'badge-success'}`}>
                  Redis Cache-Aside + DB Fallback
                </span>
              </div>

              <div className="kpi-card">
                <span className="kpi-title"><ShieldAlert size={14} /> Anomaly Engine</span>
                <span className="kpi-value">
                  {anomalies.length} Flagged
                </span>
                <span className="kpi-badge badge-warning">
                  Rolling Z-Score (3σ) & Velocity
                </span>
              </div>

              <div className="kpi-card">
                <span className="kpi-title"><Server size={14} /> Traffic Router</span>
                <span className="kpi-value">
                  {clusterNodes.filter(n => n.isHealthy).length}/{clusterNodes.length || 2} Active Nodes
                </span>
                <span className="kpi-badge badge-success">
                  Dynamic Health Probe Failover
                </span>
              </div>
            </div>

            {/* Middle Grid: Circuit Breakers & Traffic Routing */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(420px, 1fr))', gap: '20px' }}>
              {/* Circuit Breakers Card */}
              <div className="glass-panel" style={{ padding: '24px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                  <h3 style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '1.1rem' }}>
                    <Zap size={18} style={{ color: 'var(--accent-secondary)' }} />
                    Downstream Circuit Breakers (Resilience4j)
                  </h3>
                  <button className="btn-secondary btn-mini" onClick={fetchSystemIntelligence} title="Refresh metrics">
                    <RefreshCw size={12} className={systemLoading ? 'spinner' : ''} />
                    <span>Refresh</span>
                  </button>
                </div>
                <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: '16px' }}>
                  Self-healing boundary: Fails fast directly to DB/fallback when downstream latencies exceed 200ms or error rate exceeds 50%.
                </p>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                  {circuitBreakers && Object.entries(circuitBreakers).map(([name, cb]) => (
                    <div key={name} style={{ background: 'var(--bg-tertiary)', padding: '14px 18px', borderRadius: '12px', border: '1px solid var(--glass-border)' }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <div>
                          <strong style={{ fontSize: '0.95rem' }}>{name}</strong>
                          <div style={{ color: 'var(--text-muted)', fontSize: '0.78rem', marginTop: '2px' }}>
                            Buffered Calls: {cb.numberOfBufferedCalls || 0} | Failures: {cb.numberOfFailedCalls || 0}
                          </div>
                        </div>
                        <div className={`status-indicator ${cb.state === 'OPEN' ? 'status-open' : cb.state === 'HALF_OPEN' ? 'status-half-open' : 'status-closed'}`}>
                          <span className="status-dot"></span>
                          <span>{cb.state}</span>
                        </div>
                      </div>
                    </div>
                  ))}
                  {(!circuitBreakers || Object.keys(circuitBreakers).length === 0) && (
                    <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>All downstream circuit breakers operational (CLOSED).</div>
                  )}
                </div>
              </div>

              {/* Traffic Router Cluster Status Card */}
              <div className="glass-panel" style={{ padding: '24px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                  <h3 style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '1.1rem' }}>
                    <Server size={18} style={{ color: 'var(--accent-primary)' }} />
                    Intelligent Traffic Routing Topology
                  </h3>
                  <span className="kpi-badge badge-success" style={{ margin: 0 }}>Auto Failover</span>
                </div>
                <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: '16px' }}>
                  Simulated multi-node cluster topology. Automatically sheds 100% of ingress traffic away from nodes failing consecutive health probes.
                </p>

                <div className="node-grid">
                  {clusterNodes.map(node => (
                    <div key={node.nodeId} className="node-card">
                      <div className="node-card-header">
                        <span className="node-id">{node.nodeId}</span>
                        <div className={`status-indicator ${node.isHealthy ? 'status-closed' : 'status-open'}`}>
                          <span className="status-dot"></span>
                          <span>{node.isHealthy ? 'HEALTHY' : 'UNHEALTHY'}</span>
                        </div>
                      </div>
                      <div style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', fontFamily: 'monospace' }}>
                        {node.endpoint}
                      </div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.78rem', color: 'var(--text-muted)', marginTop: '4px' }}>
                        <span>Consecutive Fails: {node.consecutiveFailures}</span>
                        <span>Routed Requests: {node.totalRoutedRequests}</span>
                      </div>
                    </div>
                  ))}
                  {clusterNodes.length === 0 && (
                    <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>Cluster topology active.</div>
                  )}
                </div>
              </div>
            </div>

            {/* Bottom Panel: Anomaly Audit Stream Table */}
            <div className="glass-panel" style={{ padding: '24px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                <div>
                  <h3 style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '1.1rem' }}>
                    <ShieldAlert size={18} style={{ color: 'var(--warning)' }} />
                    Live Anomaly Detection Audit Feed
                  </h3>
                  <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginTop: '4px' }}>
                    Quarantined traffic audit trail: Bot bursts and statistical z-score volume spikes logged separately without corrupting legitimate rollups.
                  </p>
                </div>
                <span className="url-count">{anomalies.length} Events Logged</span>
              </div>

              <div className="anomaly-table-container">
                <table className="anomaly-table">
                  <thead>
                    <tr>
                      <th>Detected At</th>
                      <th>Short Code</th>
                      <th>Anomaly Type</th>
                      <th>Client IP Hash</th>
                      <th>Detection Diagnostic</th>
                    </tr>
                  </thead>
                  <tbody>
                    {anomalies.map(anom => (
                      <tr key={anom.id}>
                        <td style={{ color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>
                          {new Date(anom.detectedAt).toLocaleTimeString()}
                        </td>
                        <td>
                          <span style={{ fontFamily: 'monospace', fontWeight: 700, color: 'var(--accent-secondary)' }}>
                            {anom.shortCode}
                          </span>
                        </td>
                        <td>
                          <span className={anom.anomalyType === 'BOT_BURST_VELOCITY' ? 'badge-bot' : 'badge-spike'}>
                            {anom.anomalyType}
                          </span>
                        </td>
                        <td style={{ fontFamily: 'monospace', fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                          {anom.clientIpHash ? `${anom.clientIpHash.substring(0, 16)}...` : 'N/A'}
                        </td>
                        <td style={{ color: 'var(--text-secondary)', fontSize: '0.82rem' }}>
                          {anom.details}
                        </td>
                      </tr>
                    ))}
                    {anomalies.length === 0 && (
                      <tr>
                        <td colSpan="5" style={{ textAlign: 'center', color: 'var(--text-muted)', padding: '24px' }}>
                          No traffic anomalies detected in current window. System running within normal parameters.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        ) : (
          <>
            {/* Sidebar panel */}
            <section className="sidebar-panel">
          {/* URL Generator Form */}
          <div className="shorten-box glass-panel">
            <h2>Shorten a new URL</h2>
            <form onSubmit={handleShorten} className="shorten-form">
              <div className="shorten-input-container">
                <input 
                  type="url" 
                  placeholder="Paste your long link here..." 
                  value={longUrl}
                  onChange={(e) => setLongUrl(e.target.value)}
                  required
                />
                <Link2 size={18} className="url-input-icon" />
              </div>
              <button type="submit" className="btn-cyan" disabled={shortenLoading} style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px' }}>
                {shortenLoading ? <span className="spinner" style={{ borderColor: 'rgba(0,0,0,0.1)', borderTopColor: '#000' }}></span> : <Plus size={18} />}
                <span>Generate link</span>
              </button>
            </form>
          </div>

          {/* Quick Share Card (if just shortened) */}
          {createdUrl && (
            <div className="created-card">
              <div className="created-header">
                <CheckCircle size={18} />
                <span>Link Generated Successfully!</span>
              </div>
              <div className="created-body">
                <div className="created-qr">
                  <img src={createdUrl.qrCodeBase64} alt="QR Code" />
                </div>
                <div className="created-details">
                  <div className="created-links">
                    <span className="created-label">Short link</span>
                    <a 
                      href={createdUrl.shortUrl} 
                      target="_blank" 
                      rel="noopener noreferrer" 
                      className="created-short"
                      style={{ textDecoration: 'underline' }}
                    >
                      {createdUrl.shortUrl}
                    </a>
                  </div>
                  <div className="created-actions">
                    <a 
                      href={createdUrl.shortUrl} 
                      target="_blank" 
                      rel="noopener noreferrer" 
                      className="btn-cyan btn-mini"
                      style={{ textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
                      title="Visit short URL"
                    >
                      <ExternalLink size={12} />
                      <span>Visit</span>
                    </a>
                    <button className="btn-secondary btn-mini" onClick={() => copyToClipboard(createdUrl.shortUrl)}>
                      <Copy size={12} />
                      <span>Copy</span>
                    </button>
                    <button className="btn-secondary btn-mini" onClick={() => downloadQR(createdUrl.qrCodeBase64, createdUrl.shortCode)}>
                      <Download size={12} />
                      <span>QR</span>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* My URLs list */}
          <div className="url-list-card glass-panel">
            <div className="url-list-header">
              <h3>My Shortened Links</h3>
              <span className="url-count">{urls.length} links</span>
            </div>

            <div className="url-items">
              {urls.length === 0 ? (
                <div className="no-urls-state">
                  <Link2 size={32} />
                  <p>You haven't shortened any links yet.</p>
                </div>
              ) : (
                urls.map((item) => (
                  <div 
                    key={item.id} 
                    className={`url-item ${selectedCode === item.shortCode ? 'active' : ''}`}
                    onClick={() => setSelectedCode(item.shortCode)}
                  >
                    <div className="url-item-info">
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <a 
                          href={item.shortUrl || `${API_URL}/${item.shortCode}`} 
                          target="_blank" 
                          rel="noopener noreferrer" 
                          className="url-item-short"
                          onClick={(e) => e.stopPropagation()}
                          style={{ textDecoration: 'underline' }}
                          title="Open short link redirect in new tab"
                        >
                          {item.shortUrl || `${API_URL}/${item.shortCode}`}
                        </a>
                        <ExternalLink size={12} style={{ color: 'var(--accent-secondary)' }} />
                      </div>
                      <a 
                        href={item.originalUrl.startsWith('http') ? item.originalUrl : `https://${item.originalUrl}`}
                        target="_blank" 
                        rel="noopener noreferrer"
                        className="url-item-original" 
                        onClick={(e) => e.stopPropagation()}
                        title={`Open destination: ${item.originalUrl}`}
                        style={{ cursor: 'pointer', textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
                      >
                        <span>{maskSensitiveUrl(item.originalUrl)}</span>
                      </a>
                      <div className="url-item-meta">
                        <span className="url-item-clicks">
                          <BarChart3 size={12} />
                          {item.clickCount} clicks
                        </span>
                        <span>{new Date(item.createdAt).toLocaleDateString()}</span>
                      </div>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                      <a
                        href={item.shortUrl || `${API_URL}/${item.shortCode}`}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="btn-cyan btn-mini"
                        onClick={(e) => e.stopPropagation()}
                        title="Open link"
                        style={{ padding: '6px 10px', textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
                      >
                        <ExternalLink size={12} />
                        <span>Visit</span>
                      </a>
                      <button 
                        className="url-delete-btn"
                        onClick={(e) => handleDelete(e, item.shortCode)}
                        title="Delete link"
                      >
                        <Trash2 size={15} />
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </section>

        {/* Right workspace: Detailed Analytics Dashboard */}
        <section className="analytics-panel">
          {!selectedCode ? (
            <div className="analytics-empty-state glass-panel">
              <div className="analytics-empty-icon">
                <BarChart3 size={28} />
              </div>
              <h2>Analytics Dashboard</h2>
              <p>Select a link from the sidebar list to view detailed click tracking and geolocation analytics.</p>
            </div>
          ) : analyticsLoading && !analytics ? (
            <div className="analytics-empty-state glass-panel">
              <span className="spinner" style={{ width: '40px', height: '40px', borderColor: 'rgba(255,255,255,0.1)', borderTopColor: 'var(--accent-secondary)' }}></span>
              <h2 style={{ marginTop: '16px' }}>Loading Analytics...</h2>
            </div>
          ) : analytics ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>
              {/* Analytics Header Card */}
              <div className="analytics-header-card glass-panel">
                <div className="analytics-title-area">
                  <div className="analytics-title-info">
                    <h2>Short Link Details</h2>
                    <div className="analytics-orig-link" style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: '8px' }}>
                      <a 
                        href={getShortUrl(analytics.shortCode)} 
                        target="_blank" 
                        rel="noopener noreferrer" 
                        style={{ fontWeight: 'bold', color: 'var(--accent-secondary)', textDecoration: 'underline', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
                        title="Open short link redirect"
                      >
                        <span>{getShortUrl(analytics.shortCode)}</span>
                        <ExternalLink size={13} />
                      </a>
                      <span style={{ color: 'var(--text-muted)' }}>→</span>
                      <a 
                        href={analytics.originalUrl.startsWith('http') ? analytics.originalUrl : `https://${analytics.originalUrl}`}
                        target="_blank" 
                        rel="noopener noreferrer"
                        style={{ color: 'var(--text-secondary)', wordBreak: 'break-all', display: 'inline-flex', alignItems: 'center', gap: '4px', textDecoration: 'underline' }}
                        title={`Open destination: ${analytics.originalUrl}`}
                      >
                        <span>{maskSensitiveUrl(analytics.originalUrl)}</span>
                        <ExternalLink size={13} />
                      </a>
                    </div>
                  </div>
                  <button className="btn-secondary" style={{ padding: '10px 16px', fontSize: '0.85rem', display: 'flex', alignItems: 'center', gap: '8px' }} onClick={() => copyToClipboard(getShortUrl(analytics.shortCode))}>
                    <Copy size={14} />
                    <span>Copy short URL</span>
                  </button>
                </div>

                <div className="analytics-stats-grid">
                  <div className="stat-card">
                    <span className="stat-label">Total Clicks</span>
                    <span className="stat-value stat-value-accent">{analytics.totalClicks}</span>
                  </div>
                  <div className="stat-card">
                    <span className="stat-label">Short Code</span>
                    <span className="stat-value" style={{ fontFamily: 'monospace' }}>{analytics.shortCode}</span>
                  </div>
                  <div className="stat-card">
                    <span className="stat-label">Geolocations Tracked</span>
                    <span className="stat-value stat-value-primary">
                      {Object.keys(analytics.clicksByCountry || {}).length} countries
                    </span>
                  </div>
                </div>
              </div>

              {/* Time Series Clicks Chart */}
              <div className="chart-card chart-card-full glass-panel">
                <h3 className="chart-title">
                  <Clock size={16} />
                  <span>Click Frequency Over Time</span>
                </h3>
                <div className="chart-wrapper chart-container-full">
                  {(!analytics.clicksOverTime || analytics.clicksOverTime.length === 0) ? (
                    <div className="no-data-msg">No click timeline data recorded yet.</div>
                  ) : (
                    <ResponsiveContainer width="100%" height="100%">
                      <LineChart data={analytics.clicksOverTime} margin={{ top: 10, right: 15, left: -10, bottom: 10 }}>
                        <XAxis dataKey="date" stroke="var(--text-muted)" fontSize={11} tickLine={false} />
                        <YAxis stroke="var(--text-muted)" fontSize={11} allowDecimals={false} tickLine={false} />
                        <Tooltip 
                          contentStyle={{ background: 'var(--bg-secondary)', border: '1px solid var(--glass-border)', borderRadius: '8px' }}
                          labelStyle={{ color: 'var(--text-secondary)' }}
                        />
                        <Line type="monotone" dataKey="clicks" stroke="var(--accent-secondary)" strokeWidth={3} dot={{ fill: 'var(--accent-secondary)', r: 4 }} activeDot={{ r: 6 }} />
                      </LineChart>
                    </ResponsiveContainer>
                  )}
                </div>
              </div>

              {/* breakdowns charts grid */}
              <div className="charts-grid">
                {/* Geolocation Breakdown */}
                <div className="chart-card glass-panel">
                  <h3 className="chart-title">
                    <Globe size={16} />
                    <span>Top Countries</span>
                  </h3>
                  <div className="chart-wrapper">
                    {(!analytics.clicksByCountry || Object.keys(analytics.clicksByCountry).length === 0) ? (
                      <div className="no-data-msg">No geo data available yet.</div>
                    ) : (
                      <ResponsiveContainer width="100%" height="100%">
                        <BarChart 
                          data={Object.entries(analytics.clicksByCountry).map(([country, count]) => {
                            const details = getCountryDetails(country);
                            return {
                              country: `${details.flag} ${details.name}`,
                              count
                            };
                          })}
                          layout="vertical"
                          margin={{ top: 5, right: 15, left: 10, bottom: 5 }}
                        >
                          <XAxis type="number" stroke="var(--text-muted)" fontSize={11} tickLine={false} />
                          <YAxis dataKey="country" type="category" stroke="var(--text-muted)" fontSize={11} tickLine={false} width={130} />
                          <Tooltip contentStyle={{ background: 'var(--bg-secondary)', border: '1px solid var(--glass-border)', borderRadius: '8px' }} />
                          <Bar dataKey="count" fill="var(--accent-primary)" radius={[0, 4, 4, 0]}>
                            {Object.keys(analytics.clicksByCountry).map((entry, index) => (
                              <Cell key={`cell-${index}`} fill={index % 2 === 0 ? 'var(--accent-primary)' : 'var(--accent-secondary)'} />
                            ))}
                          </Bar>
                        </BarChart>
                      </ResponsiveContainer>
                    )}
                  </div>
                </div>

                {/* Device Breakdown */}
                <div className="chart-card glass-panel">
                  <h3 className="chart-title">
                    <Monitor size={16} />
                    <span>Device Platforms</span>
                  </h3>
                  <div className="chart-wrapper" style={{ display: 'flex', alignItems: 'center' }}>
                    {(!analytics.clicksByDeviceType || Object.keys(analytics.clicksByDeviceType).length === 0) ? (
                      <div className="no-data-msg" style={{ width: '100%' }}>No device stats available yet.</div>
                    ) : (
                      <div className="device-breakdown-list" style={{ width: '100%' }}>
                        {Object.entries(analytics.clicksByDeviceType).map(([device, count]) => {
                          const total = Object.values(analytics.clicksByDeviceType).reduce((a, b) => a + b, 0);
                          const percentage = Math.round((count / total) * 100);
                          return (
                            <div key={device} className="device-item">
                              <div className="device-label-row">
                                <span style={{ textTransform: 'capitalize' }}>{device || 'Unknown'}</span>
                                <span style={{ color: 'var(--accent-secondary)' }}>{count} ({percentage}%)</span>
                              </div>
                              <div className="device-bar-container">
                                <div className="device-bar-fill" style={{ width: `${percentage}%` }}></div>
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </div>
                </div>

                {/* Browser Breakdown */}
                <div className="chart-card glass-panel">
                  <h3 className="chart-title">
                    <Globe size={16} />
                    <span>Web Browsers</span>
                  </h3>
                  <div className="chart-wrapper">
                    {(!analytics.clicksByBrowser || Object.keys(analytics.clicksByBrowser).length === 0) ? (
                      <div className="no-data-msg">No browser stats available yet.</div>
                    ) : (
                      <ResponsiveContainer width="100%" height="100%">
                        <BarChart 
                          data={Object.entries(analytics.clicksByBrowser).map(([browser, count]) => ({ browser, count }))}
                          margin={{ top: 10, right: 10, left: -10, bottom: 20 }}
                        >
                          <XAxis dataKey="browser" stroke="var(--text-muted)" fontSize={10} tickLine={false} />
                          <YAxis stroke="var(--text-muted)" fontSize={11} tickLine={false} />
                          <Tooltip contentStyle={{ background: 'var(--bg-secondary)', border: '1px solid var(--glass-border)', borderRadius: '8px' }} />
                          <Bar dataKey="count" fill="var(--accent-secondary)" radius={[4, 4, 0, 0]} />
                        </BarChart>
                      </ResponsiveContainer>
                    )}
                  </div>
                </div>

                {/* OS Breakdown */}
                <div className="chart-card glass-panel">
                  <h3 className="chart-title">
                    <Laptop size={16} />
                    <span>Operating Systems</span>
                  </h3>
                  <div className="chart-wrapper">
                    {(!analytics.clicksByOS || Object.keys(analytics.clicksByOS).length === 0) ? (
                      <div className="no-data-msg">No OS stats available yet.</div>
                    ) : (
                      <ResponsiveContainer width="100%" height="100%">
                        <BarChart 
                          data={Object.entries(analytics.clicksByOS).map(([os, count]) => ({ os, count }))}
                          margin={{ top: 10, right: 10, left: -10, bottom: 20 }}
                        >
                          <XAxis dataKey="os" stroke="var(--text-muted)" fontSize={10} tickLine={false} />
                          <YAxis stroke="var(--text-muted)" fontSize={11} tickLine={false} />
                          <Tooltip contentStyle={{ background: 'var(--bg-secondary)', border: '1px solid var(--glass-border)', borderRadius: '8px' }} />
                          <Bar dataKey="count" fill="var(--accent-primary)" radius={[4, 4, 0, 0]} />
                        </BarChart>
                      </ResponsiveContainer>
                    )}
                  </div>
                </div>
              </div>
            </div>
          ) : null}
        </section>
        </>
        )}
      </main>

      {/* Floating toast notification */}
      {toast && (
        <div className="toast">
          {toast.type === 'error' ? <AlertCircle size={18} style={{ color: 'var(--danger)' }} /> : <CheckCircle size={18} style={{ color: 'var(--success)' }} />}
          <span>{toast.message}</span>
        </div>
      )}
    </div>
  );
}
